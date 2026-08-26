package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.management.openmbean.CompositeData;

import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

/**
 * Contract tests for {@link ValidationControl}: ранее отсутствующий владелец давал
 * sentinel {@code configVersion = -1L} и {@code configSource = "unknown"}; теперь — честный
 * presence-дискриминатор и нейтральный placeholder.
 */
class ValidationControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void ownerAbsentGivesPlaceholderValuesNotSentinel() {
        ValidationControl control = new ValidationControl(new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        CompositeData observation = control.getValidationObservation();

        assertThat((Boolean) observation.get(
                GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT)).isFalse();
        assertThat((Long) observation.get(
                GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Boolean) observation.get(
                GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_ENABLED)).isFalse();
        assertThat((Long) observation.get(
                GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_VERSION)).isEqualTo(0L);
        assertThat((String) observation.get(
                GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_LAST_UPDATED_SOURCE))
                .isEmpty();
    }

    @Test
    void ownerPresentReflectsProcessorStateAndPositiveGeneration() {
        OwnerRegistrationSlot<ValidatingSpanProcessor> slot = new OwnerRegistrationSlot<>();
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(
                TracingSettingsSource.isolatedValidation(true, true, false));
        try (SpanProcessorHarness harness = SpanProcessorHarness.of(processor, Resource.empty())) {
            harness.tracer("test").spanBuilder("missing-attributes").startSpan().end();
        }
        slot.replace(processor);
        ValidationControl control = new ValidationControl(slot, AGENT_INSTANCE_ID);

        CompositeData observation = control.getValidationObservation();

        assertThat((Boolean) observation.get(
                GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT)).isTrue();
        assertThat((Long) observation.get(
                GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_GENERATION)).isPositive();
        assertThat((Boolean) observation.get(
                GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_ENABLED)).isTrue();
        assertThat((Long) observation.get(
                GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_VIOLATIONS_TOTAL)).isEqualTo(1L);
    }
}
