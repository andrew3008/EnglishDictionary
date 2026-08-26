package space.br1440.platform.tracing.otel.javaagent.jmx.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.watchdog.SpanWatchdogProcessor;

class ProcessorMetricsControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void allThreeOwnersAbsentEncodePresenceFalse() {
        ProcessorMetricsControl control = new ProcessorMetricsControl(
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID);

        CompositeData observation = control.getProcessorMetricsObservation();
        CompositeData watchdog = (CompositeData) observation.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_WATCHDOG);
        CompositeData composite = (CompositeData) observation.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_COMPOSITE);
        CompositeData metrics = (CompositeData) observation.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_METRICS);

        assertThat((Boolean) watchdog.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Boolean) composite.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Boolean) metrics.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Long) watchdog.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Long) composite.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Long) metrics.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_GENERATION)).isEqualTo(0L);
    }

    @Test
    void presentWatchdogOwnerExportsLiveCounters() {
        // given
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), 10_000, 1_024, 60_000);
        OwnerRegistrationSlot<SpanWatchdogProcessor> slot = new OwnerRegistrationSlot<>();
        slot.replace(watchdog);
        ProcessorMetricsControl control = new ProcessorMetricsControl(
                slot,
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID);

        // when
        CompositeData observation = control.getProcessorMetricsObservation();
        CompositeData watchdogValues = (CompositeData) observation.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_WATCHDOG);

        // then
        assertThat((Boolean) watchdogValues.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_OWNER_PRESENT)).isTrue();
        assertThat((Long) watchdogValues.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_FORCED_SPAN_CLOSES)).isZero();
        assertThat((Integer) watchdogValues.get(
                GeneratedJmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_ACTIVE_SPAN_COUNT)).isZero();
        watchdog.shutdown();
    }
}
