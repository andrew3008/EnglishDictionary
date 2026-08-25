package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
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
                Duration.ofMillis(10), Duration.ofMinutes(1), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("jmx");
        Span hung = tracer.spanBuilder("hung").startSpan();
        clock.addAndGet(Duration.ofMillis(11).toNanos());
        watchdog.scanForTesting();

        OwnerRegistrationSlot<SpanWatchdogProcessor> slot = new OwnerRegistrationSlot<>();
        slot.replace(watchdog);
        ProcessorMetricsControl control = new ProcessorMetricsControl(
                slot,
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                UUID.randomUUID());

        // when
        CompositeData observation = control.getProcessorMetricsObservation();
        CompositeData watchdogValues = (CompositeData) observation.get(
                JmxObservationWireContract.PROCESSOR_METRICS_ITEM_WATCHDOG);

        // then
        assertThat((Boolean) watchdogValues.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isTrue();
        assertThat((Long) watchdogValues.get(
                JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_FORCED_SPAN_CLOSES)
        ).isEqualTo(1L);
        assertThat((Integer) watchdogValues.get(
                JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_ACTIVE_SPAN_COUNT)
        ).isZero();
        hung.end();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }
}
