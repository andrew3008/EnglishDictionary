package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

class ValidationObservationSourceOffsetNanosTest {

    private static final long INDUCED_CAPTURE_DELAY_MILLIS = 150L;
    private static final double LOWER_BOUND_FRACTION = 0.5;

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void offsetNanosReflectsActualCaptureLatency_notTimeBeforeCapture() {
        // given
        UUID agentInstanceId = UUID.randomUUID();

        ValidatingSpanProcessor validating = mock(ValidatingSpanProcessor.class);
        when(validating.isEnabled()).thenReturn(true);
        when(validating.isStrict()).thenReturn(false);
        when(validating.isStrictRuntimeAllowed()).thenReturn(true);
        when(validating.getPolicyVersion()).thenReturn(7L);
        when(validating.getPolicySource()).thenReturn("test-policy-source");

        OwnerRegistrationState.Present<ValidatingSpanProcessor> presentState =
                new OwnerRegistrationState.Present<>(validating, 3L);

        @SuppressWarnings("unchecked")
        OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot = mock(OwnerRegistrationSlot.class);
        when(validatingSlot.capture()).thenAnswer(invocation -> {
            Thread.sleep(INDUCED_CAPTURE_DELAY_MILLIS);
            return presentState;
        });

        ValidationObservationSource source = new ValidationObservationSource(validatingSlot, agentInstanceId);

        // when
        CompositeData observation = source.getValidationObservation();

        // then
        long offsetNanos = (long) observation.get(JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_CAPTURED_OFFSET_NANOS);
        long inducedDelayNanos = TimeUnit.MILLISECONDS.toNanos(INDUCED_CAPTURE_DELAY_MILLIS);
        long lowerBoundNanos = (long) (inducedDelayNanos * LOWER_BOUND_FRACTION);

        assertThat(offsetNanos)
                .as("offsetNanos must capture the real duration of validatingSlot.capture(); "
                        + "a value below the induced delay indicates the timestamp was taken "
                        + "before capture() executed, not after")
                .isGreaterThanOrEqualTo(lowerBoundNanos);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void offsetNanosStaysNearZero_whenCaptureIsInstantaneous() {
        // given
        UUID agentInstanceId = UUID.randomUUID();

        OwnerRegistrationState.Absent<ValidatingSpanProcessor> absentState =
                new OwnerRegistrationState.Absent<>(0L);

        @SuppressWarnings("unchecked")
        OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot = mock(OwnerRegistrationSlot.class);
        when(validatingSlot.capture()).thenReturn(absentState);

        ValidationObservationSource source = new ValidationObservationSource(validatingSlot, agentInstanceId);

        // when
        CompositeData observation = source.getValidationObservation();

        // then
        long offsetNanos = (long) observation.get(JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_CAPTURED_OFFSET_NANOS);

        assertThat(offsetNanos)
                .as("offsetNanos should stay small when capture() is effectively instantaneous")
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(INDUCED_CAPTURE_DELAY_MILLIS / 2));
    }

    @Test
    void ownerPresentFlagAndPayloadFields_reflectCapturedState() {
        // given
        UUID agentInstanceId = UUID.randomUUID();

        ValidatingSpanProcessor validating = mock(ValidatingSpanProcessor.class);
        when(validating.isEnabled()).thenReturn(true);
        when(validating.isStrict()).thenReturn(true);
        when(validating.isStrictRuntimeAllowed()).thenReturn(false);
        when(validating.getPolicyVersion()).thenReturn(42L);
        when(validating.getPolicySource()).thenReturn("dynamic-config");

        OwnerRegistrationState.Present<ValidatingSpanProcessor> presentState =
                new OwnerRegistrationState.Present<>(validating, 1L);

        @SuppressWarnings("unchecked")
        OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot = mock(OwnerRegistrationSlot.class);
        when(validatingSlot.capture()).thenReturn(presentState);

        ValidationObservationSource source = new ValidationObservationSource(validatingSlot, agentInstanceId);

        // when
        CompositeData observation = source.getValidationObservation();

        // then
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT)).isTrue();

        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_ENABLED)).isTrue();

        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_STRICT)).isTrue();

        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_STRICT_RUNTIME_ALLOWED))
                .isFalse();

        assertThat((Long) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_VERSION))
                .isEqualTo(42L);

        assertThat((String) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_LAST_UPDATED_SOURCE))
                .isEqualTo("dynamic-config");
    }

    @Test
    void absentOwner_yieldsDefaultSnapshot_notStalePreviousValues() {
        // given
        UUID agentInstanceId = UUID.randomUUID();

        OwnerRegistrationState.Absent<ValidatingSpanProcessor> absentState = new OwnerRegistrationState.Absent<>(0L);

        @SuppressWarnings("unchecked")
        OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot = mock(OwnerRegistrationSlot.class);
        when(validatingSlot.capture()).thenReturn(absentState);

        ValidationObservationSource source = new ValidationObservationSource(validatingSlot, agentInstanceId);

        // when
        CompositeData observation = source.getValidationObservation();

        // then
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT)).isFalse();

        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_ENABLED)).isFalse();

        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_STRICT)).isFalse();

        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_STRICT_RUNTIME_ALLOWED))
                .isFalse();

        assertThat((Long) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_VERSION))
                .isEqualTo(0L);

        assertThat((String) observation.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_LAST_UPDATED_SOURCE))
                .isEmpty();
    }
}
