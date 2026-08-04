package space.br1440.platform.tracing.otel.javaagent.jmx.metrics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.MetricsSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanWatchdogProcessor;

import javax.management.openmbean.CompositeData;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link PlatformProcessorMetricsControl}: три независимых владельца
 * кодируют presence в собственных sub-composite.
 */
class PlatformProcessorMetricsControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void allThreeOwnersAbsentEncodePresenceFalse() {
        PlatformProcessorMetricsControl control = new PlatformProcessorMetricsControl(
                new OwnerRegistrationSlot<SpanWatchdogProcessor>(),
                new OwnerRegistrationSlot<PlatformCompositeSpanProcessor>(),
                new OwnerRegistrationSlot<MetricsSpanProcessor>(),
                AGENT_INSTANCE_ID);

        CompositeData observation = control.getProcessorMetricsObservation();
        CompositeData watchdog = (CompositeData) observation.get(JmxObservationWireContract.PROCESSOR_METRICS_ITEM_WATCHDOG);
        CompositeData composite = (CompositeData) observation.get(JmxObservationWireContract.PROCESSOR_METRICS_ITEM_COMPOSITE);
        CompositeData metrics = (CompositeData) observation.get(JmxObservationWireContract.PROCESSOR_METRICS_ITEM_METRICS);

        assertThat((Boolean) watchdog.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isFalse();
        assertThat((Boolean) composite.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isFalse();
        assertThat((Boolean) metrics.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isFalse();
        assertThat((Long) watchdog.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Long) composite.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Long) metrics.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isEqualTo(0L);
    }
}
