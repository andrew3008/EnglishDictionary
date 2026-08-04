package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link PlatformScrubbingControl}: владелец заменяем, поэтому
 * ключевая проверка — presence-дискриминатор вместо прежнего sentinel {@code -1L}
 * при отсутствующем {@code ScrubbingSpanProcessor}.
 */
class PlatformScrubbingControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void ownerAbsentGivesPlaceholderValuesNotSentinel() {
        PlatformScrubbingControl control = new PlatformScrubbingControl(new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        CompositeData observation = control.getScrubbingObservation();

        assertThat((Boolean) observation.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isFalse();
        assertThat((Long) observation.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Boolean) observation.get(JmxObservationWireContract.SCRUBBING_ITEM_ENABLED)).isFalse();
        assertThat((Long) observation.get(JmxObservationWireContract.SCRUBBING_ITEM_POLICY_VERSION)).isEqualTo(0L);
        TabularData metrics = (TabularData) observation.get(JmxObservationWireContract.SCRUBBING_ITEM_METRICS);
        assertThat(metrics.size()).isZero();
    }

    @Test
    void ownerPresentReflectsProcessorStateAndPositiveGeneration() {
        OwnerRegistrationSlot<ScrubbingSpanProcessor> slot = new OwnerRegistrationSlot<>();
        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(List.of());
        slot.replace(processor);
        PlatformScrubbingControl control = new PlatformScrubbingControl(slot, AGENT_INSTANCE_ID);

        CompositeData observation = control.getScrubbingObservation();

        assertThat((Boolean) observation.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isTrue();
        assertThat((Long) observation.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isPositive();
        assertThat((Boolean) observation.get(JmxObservationWireContract.SCRUBBING_ITEM_ENABLED)).isTrue();
    }
}
