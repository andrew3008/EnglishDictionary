package space.br1440.platform.tracing.otel.control.protocol;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RuntimePolicyControlDomainValidator} that focus on
 * validation-mode domain rules (exercising the
 * {@link ValidationModePolicyValidator} delegation path).
 *
 * <p>The basic sampling cases live in
 * {@link RuntimePolicyControlDomainValidatorTest}; this companion class
 * covers the validation-mode rules to keep each test file focused.
 */
class RuntimePolicyControlDomainValidatorValidationModeTest {

    @Test
    void acceptsPayloadWithValidationModeLogOnlyAndNoStrictKey() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "LOG_ONLY");

        TracingControlDomainValidationResult result =
                RuntimePolicyControlDomainValidator.validate(payload);

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void acceptsPayloadWithValidationModeStrictAndMatchingStrictFlag() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE,   "STRICT");
        payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);

        TracingControlDomainValidationResult result =
                RuntimePolicyControlDomainValidator.validate(payload);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsUnknownValidationModeViaDomainValidator() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "VERBOSE");

        TracingControlDomainValidationResult result =
                RuntimePolicyControlDomainValidator.validate(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst()).contains("not a recognised mode");
    }

    @Test
    void rejectsConflictingModeAndStrictFlagViaDomainValidator() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE,   "LOG_ONLY");
        payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true); // conflict

        TracingControlDomainValidationResult result =
                RuntimePolicyControlDomainValidator.validate(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst()).contains("conflicts with");
    }

    @Test
    void mergesSamplingAndValidationModeViolationsInSinglePass() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO,    2.0d); // sampling violation
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE,   "LOG_ONLY");
        payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true); // mode conflict

        TracingControlDomainValidationResult result =
                RuntimePolicyControlDomainValidator.validate(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(2);
    }
}
