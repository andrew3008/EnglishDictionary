package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

import javax.management.openmbean.CompositeData;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link PlatformValidationControl}: ранее отсутствующий владелец давал
 * sentinel {@code configVersion = -1L} и {@code configSource = "unknown"}; теперь — честный
 * presence-дискриминатор и нейтральный placeholder.
 */
class PlatformValidationControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void ownerAbsentGivesPlaceholderValuesNotSentinel() {
        PlatformValidationControl control = new PlatformValidationControl(new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        CompositeData observation = control.getValidationObservation();

        assertThat((Boolean) observation.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isFalse();
        assertThat((Long) observation.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Boolean) observation.get(JmxObservationWireContract.VALIDATION_ITEM_ENABLED)).isFalse();
        assertThat((Long) observation.get(JmxObservationWireContract.VALIDATION_ITEM_CONFIG_VERSION)).isEqualTo(0L);
        assertThat((String) observation.get(JmxObservationWireContract.VALIDATION_ITEM_CONFIG_LAST_UPDATED_SOURCE)).isEmpty();
    }

    @Test
    void ownerPresentReflectsProcessorStateAndPositiveGeneration() {
        OwnerRegistrationSlot<ValidatingSpanProcessor> slot = new OwnerRegistrationSlot<>();
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(true);
        slot.replace(processor);
        PlatformValidationControl control = new PlatformValidationControl(slot, AGENT_INSTANCE_ID);

        CompositeData observation = control.getValidationObservation();

        assertThat((Boolean) observation.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isTrue();
        assertThat((Long) observation.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isPositive();
        assertThat((Boolean) observation.get(JmxObservationWireContract.VALIDATION_ITEM_ENABLED)).isTrue();
    }
}
