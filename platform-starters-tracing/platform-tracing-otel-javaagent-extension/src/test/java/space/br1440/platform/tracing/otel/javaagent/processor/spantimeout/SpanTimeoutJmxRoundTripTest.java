package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class SpanTimeoutJmxRoundTripTest {

    @Test
    void presentOwnerExportsForcedCloseCounters() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(10),
                Duration.ofMinutes(1),
                clock::get,
                10_000,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
        );

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();

        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("jmx");
            Span hung = tracer.spanBuilder("hung").startSpan();
            clock.addAndGet(Duration.ofMillis(11).toNanos());
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            OwnerRegistrationSlot<SpanTimeoutProcessor> slot = new OwnerRegistrationSlot<>();
            slot.replace(spanTimeout);
            ProcessorMetricsObservationSource source = new ProcessorMetricsObservationSource(
                    slot,
                    new OwnerRegistrationSlot<>(),
                    new OwnerRegistrationSlot<>(),
                    AgentInstanceId.random()
            );

            // when
            CompositeData observation = source.getProcessorMetricsObservation();
            CompositeData spanTimeoutValues = (CompositeData) observation.get(
                    JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_SPAN_TIMEOUT
            );

            // then
            assertThat((Boolean) spanTimeoutValues.get(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_OWNER_PRESENT)).isTrue();
            assertThat((Long) spanTimeoutValues.get(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_FORCED_SPAN_END_ATTEMPTS_TOTAL)).isEqualTo(1L);
            assertThat((Integer) spanTimeoutValues.get(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_ACTIVE_SPAN_COUNT)).isZero();

            hung.end();
            spanTimeout.shutdown();
        }
    }
}
