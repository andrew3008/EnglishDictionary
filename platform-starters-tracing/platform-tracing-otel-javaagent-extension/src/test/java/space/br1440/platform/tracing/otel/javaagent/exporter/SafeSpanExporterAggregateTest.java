package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.OtlpRequestSizeLimitingSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation.Outcome;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SafeSpanExporterAggregateTest {

    private static final int REQUEST_LIMIT = 512;

    @Test
    void creditsEveryAggregateCategoryDespiteTransportFailure() {
        // given
        try (SafeSpanExporter safe = exporter(accepted(), accepted(), rejected(), failed(), undetermined())) {
            // when
            CompletableResultCode result = safe.export(List.of(span(), span(), span(), span(), span(),
                    oversized(), unmeasurable(new IllegalStateException("sensitive detail"))));

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCHES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isEqualTo(2);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SIZE_MEASUREMENT_FAILURES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SPLIT_EXPORT_BATCHES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_ACCEPTED_UNKNOWN_BATCHES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_PARTIAL_SUCCESS_BATCHES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot())
                    .containsEntry("oversized_spans_dropped", 1L)
                    .containsEntry("size_measurement_failures", 1L)
                    .containsEntry("split_export_batches", 1L);
        }
    }

    @Test
    void localDropsHaveSeparateAccountingAndNumericOnlyDiagnostic() {
        // given
        try (SafeSpanExporter safe = exporter(); AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            CompletableResultCode result = safe.export(List.of(oversized(), oversized()));

            // then
            assertThat(result.isSuccess()).isTrue();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isEqualTo(2);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SIZE_MEASUREMENT_FAILURES, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SPLIT_EXPORT_BATCHES, 0L).longValue()).isZero();
            assertThat(capture.events())
                    .filteredOn(event -> "SAFE_EXPORT_OVERSIZED_SPAN_DROPPED".equals(event.eventName()))
                    .singleElement().satisfies(event -> {
                        assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
                        assertThat(event.fields()).containsOnlyKeys("count", "limitBytes");
                        assertThat(event.fields()).containsEntry("count", "2");
                        assertThat(event.fields()).containsEntry("limitBytes",
                                Long.toString(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES));
                        assertThat(event.throwableDigest()).isNull();
                    });
            assertThat(capture.rendered()).doesNotContain("sensitive-operation");
        }
    }

    @Test
    void measurementFailuresHaveSeparateAccountingAndKeepRecoveryOpen() {
        // given
        IllegalArgumentException firstCause = new IllegalArgumentException("sensitive-operation");
        try (SafeSpanExporter safe = exporter(failed(), accepted());
             AgentLogCapture capture = AgentLogCapture.installed()) {
            safe.export(List.of(span()));

            // when
            CompletableResultCode result = safe.export(List.of(unmeasurable(firstCause),
                    unmeasurable(new NullPointerException("sensitive second cause"))));

            // then
            assertThat(result.isSuccess()).isTrue();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SIZE_MEASUREMENT_FAILURES, 0L).longValue()).isEqualTo(2);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isZero();
            assertThat(recoveryEvents(capture)).isEmpty();
            assertThat(capture.events())
                    .filteredOn(event -> "SAFE_EXPORT_SIZE_MEASUREMENT_FAILED".equals(event.eventName()))
                    .singleElement().satisfies(event -> {
                        assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
                        assertThat(event.fields()).containsOnlyKeys("count");
                        assertThat(event.fields()).containsEntry("count", "2");
                        assertThat(event.errorType()).isEqualTo(IllegalArgumentException.class.getName());
                        assertThat(event.throwableDigest()).contains(IllegalArgumentException.class.getName());
                    });
            assertThat(capture.rendered()).doesNotContain("sensitive-operation", "sensitive second cause");

            // when
            safe.export(List.of(span()));

            // then
            assertThat(recoveryEvents(capture)).singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "1"));
        }
    }

    @Test
    void mixedFailureAndLocalDropsKeepTheOutageOpen() {
        // given
        try (SafeSpanExporter safe = exporter(failed(), failed(), accepted(), failed(), accepted(), rejected());
             AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(List.of(span(), span()));
            safe.export(List.of(span(), span()));
            safe.export(List.of(oversized(), oversized()));

            // then
            assertThat(recoveryEvents(capture)).isEmpty();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, 0L).longValue()).isEqualTo(2);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, 0L).longValue()).isEqualTo(3);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isEqualTo(1);

            // when
            safe.export(List.of(span(), span()));

            // then
            assertThat(recoveryEvents(capture)).singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "2"));
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isEqualTo(2);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @MethodSource("successfulResponses")
    void successfulObservedLeafCanCloseRecovery(Step outcome) {
        // given
        try (SafeSpanExporter safe = exporter(failed(), outcome);
             AgentLogCapture capture = AgentLogCapture.installed()) {
            safe.export(List.of(span()));

            // when
            safe.export(List.of(span()));

            // then
            assertThat(recoveryEvents(capture)).hasSize(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SPLIT_EXPORT_BATCHES, 0L).longValue()).isZero();
        }
    }

    @Test
    void lateCompletionAppliesAggregateOnlyOnce() {
        // given
        CompletableResultCode pending = new CompletableResultCode();
        try (SafeSpanExporter safe = exporter(new Step(Outcome.ALL_ACCEPTED, pending))) {
            CompletableResultCode returned = safe.export(List.of(span(), oversized()));
            assertThat(returned.isDone()).isFalse();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isZero();

            // when
            pending.succeed();
            pending.succeed();

            // then
            assertThat(returned.isSuccess()).isTrue();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SPLIT_EXPORT_BATCHES, 0L).longValue()).isZero();
        }
    }

    private static Stream<Step> successfulResponses() {
        return Stream.of(accepted(), rejected(), undetermined());
    }

    private static Step accepted() {
        return new Step(Outcome.ALL_ACCEPTED, CompletableResultCode.ofSuccess());
    }

    private static Step rejected() {
        return new Step(Outcome.PARTIALLY_REJECTED, CompletableResultCode.ofSuccess());
    }

    private static Step undetermined() {
        return new Step(Outcome.NOT_DETERMINED, CompletableResultCode.ofSuccess());
    }

    private static Step failed() {
        return new Step(Outcome.NOT_OBSERVED, CompletableResultCode.ofFailure());
    }

    private static SafeSpanExporter exporter(Step... steps) {
        return new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(new SequenceExporter(steps), REQUEST_LIMIT),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled()
        );
    }

    private static SpanData span() {
        return ExporterTestSpans.baseSpan("operation")
                .setAttributes(Attributes.of(AttributeKey.stringKey("payload"), "x".repeat(300)))
                .build();
    }

    private static SpanData oversized() {
        return ExporterTestSpans.baseSpan("sensitive-operation")
                .setAttributes(Attributes.of(AttributeKey.stringKey("payload"), "x".repeat(1024)))
                .build();
    }

    private static SpanData unmeasurable(RuntimeException failure) {
        return new DelegatingSpanData(span()) {
            @Override
            public List<EventData> getEvents() {
                throw failure;
            }
        };
    }

    private static List<CapturedLogEvent> recoveryEvents(AgentLogCapture capture) {
        return capture.events().stream()
                .filter(event -> "SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES".equals(event.eventName())).toList();
    }

    private record Step(Outcome outcome, CompletableResultCode result) {
    }

    private static final class SequenceExporter implements SpanExporter {

        private final Deque<Step> steps;

        private SequenceExporter(Step... steps) {
            this.steps = new ArrayDeque<>(List.of(steps));
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            Step step = steps.removeFirst();
            ExportResponseObservation observation = Objects.requireNonNull(ExportResponseScope.current());
            switch (step.outcome()) {
                case ALL_ACCEPTED -> observation.recordAllAccepted();
                case PARTIALLY_REJECTED -> observation.recordRejected(1);
                case NOT_DETERMINED -> observation.recordNotDetermined();
                case NOT_OBSERVED -> { }
            }
            return step.result();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
