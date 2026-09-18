package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.management.openmbean.CompositeData;

import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

class ValidationObservationSourceTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void ownerAbsentGivesPlaceholderValuesNotSentinel() {
        // given
        ValidationObservationSource source = new ValidationObservationSource(
                new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID
        );

        // when
        CompositeData observation = source.getValidationObservation();

        // then
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT)).isFalse();

        assertThat((Long) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_GENERATION)).isEqualTo(0L);

        assertThat((String) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_LEVEL)).isEmpty();

        assertThat((Long) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_VERSION)).isEqualTo(0L);

        assertThat((String) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_LAST_UPDATED_SOURCE))
                .isEmpty();
    }

    @Test
    void ownerPresentReflectsProcessorStateAndPositiveGeneration() {
        // given
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(
                TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.ERROR)
        );

        try (SpanProcessorHarness harness = SpanProcessorHarness.of(processor, Resource.empty())) {
            harness.tracer("test").spanBuilder("missing-attributes").startSpan().end();
        }

        OwnerRegistrationSlot<ValidatingSpanProcessor> slot = new OwnerRegistrationSlot<>();
        slot.replace(processor);

        ValidationObservationSource source = new ValidationObservationSource(slot, AGENT_INSTANCE_ID);

        // when
        CompositeData observation = source.getValidationObservation();

        // then
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT)).isTrue();

        assertThat((Long) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_GENERATION)).isPositive();

        assertThat((String) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_LEVEL))
                .isEqualTo(SpanAttributeValidationLevel.ERROR.name());

        assertThat((Long) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_VIOLATIONS_TOTAL)).isEqualTo(1L);
    }
}
