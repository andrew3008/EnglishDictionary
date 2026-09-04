package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class SpanWatchdogJmxRoundTripTest {

    @Test
    void presentOwnerExportsForcedCloseCounters() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMillis(10), 
                Duration.ofMinutes(1), 
                clock::get, 
                10_000, 
                Integer.MAX_VALUE, 
                Integer.MAX_VALUE
        );
        
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();

        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("jmx");
            Span hung = tracer.spanBuilder("hung").startSpan();
            clock.addAndGet(Duration.ofMillis(11).toNanos());
            watchdog.scanForTesting();

            OwnerRegistrationSlot<SpanWatchdogProcessor> slot = new OwnerRegistrationSlot<>();
            slot.replace(watchdog);
            ProcessorMetricsObservationSource source = new ProcessorMetricsObservationSource(
                    slot,
                    new OwnerRegistrationSlot<>(),
                    new OwnerRegistrationSlot<>(),
                    UUID.randomUUID()
                );

            // when
            CompositeData observation = source.getProcessorMetricsObservation();
            CompositeData watchdogValues = (CompositeData) observation.get(
                    JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_WATCHDOG
                );

            // then
            assertThat((Boolean) watchdogValues.get(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_OWNER_PRESENT)).isTrue();
            assertThat((Long) watchdogValues.get(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_FORCED_SPAN_CLOSES)).isEqualTo(1L);
            assertThat((Integer) watchdogValues.get(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_ACTIVE_SPAN_COUNT)).isZero();
            hung.end();
            watchdog.shutdown();
        }
    }
}
