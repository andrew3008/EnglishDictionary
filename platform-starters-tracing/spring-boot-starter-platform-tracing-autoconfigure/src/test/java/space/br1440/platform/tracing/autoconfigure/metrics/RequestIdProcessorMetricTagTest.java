package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorCompositeValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.ObservationCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.RequestIdPropagatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;

class RequestIdProcessorMetricTagTest {

    private static final String AGENT_INSTANCE_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String CANONICAL_REQUEST_ID = "01234567-89ab-4cde-8fab-0123456789ab";
    private static final String ERRORS_BY_NAME = "platform.tracing.processor.composite.errors_by_name";
    private static final String PROCESSOR_TAG = "request_id_propagation";

    @Test
    void binderPublishesRequestIdPropagationErrorsWithoutTheRequestId() {
        // given
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(
                List.of(new RequestIdPropagatingSpanProcessor())
        )) {
            AttributeKey<String> requestIdKey = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REQUEST_ID);
            Span parent = mock(Span.class, withSettings()
                    .extraInterfaces(ReadableSpan.class)
                    .defaultAnswer(CALLS_REAL_METHODS));
            ReadableSpan readableParent = (ReadableSpan) parent;
            doReturn(CANONICAL_REQUEST_ID).when(readableParent).getAttribute(requestIdKey);
            ReadWriteSpan child = mock(ReadWriteSpan.class);
            when(child.getAttribute(any())).thenReturn(null);
            doThrow(new IllegalStateException("attribute write failed"))
                    .when(child)
                    .setAttribute(requestIdKey, CANONICAL_REQUEST_ID);

            // when
            composite.onStart(Context.root().with(parent), child);
            Map<String, Long> counts = composite.getProcessorErrorCounts();

            // then
            assertThat(counts).containsEntry(PROCESSOR_TAG, 1L);
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
            when(client.processorMetrics().observation()).thenReturn(Optional.of(new ProcessorMetricsObservation(
                    new ObservationCapture(AGENT_INSTANCE_ID, 1L, 1_000L, 1_001L),
                    new JmxOwnerState.Absent<>(0L),
                    new JmxOwnerState.Present<>(new ProcessorCompositeValues(total, counts), 1L),
                    new JmxOwnerState.Absent<>(0L)
            )));
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new PlatformTracingProcessorMetricsBinder(client).bindTo(registry);

            assertThat(registry.get(ERRORS_BY_NAME)
                    .tag("processor", PROCESSOR_TAG)
                    .functionCounter()
                    .count()).isEqualTo(1.0);
            assertThat(registry.getMeters()).isNotEmpty();
            for (Meter meter : registry.getMeters()) {
                for (Tag tag : meter.getId().getTags()) {
                    assertThat(tag.getKey()).isNotEqualTo(PlatformAttributes.PLATFORM_REQUEST_ID);
                    assertThat(tag.getValue()).isNotEqualTo(CANONICAL_REQUEST_ID);
                }
            }
        }
    }
}
