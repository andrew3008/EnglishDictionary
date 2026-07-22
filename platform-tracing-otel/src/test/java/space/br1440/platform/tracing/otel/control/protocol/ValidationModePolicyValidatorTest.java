package space.br1440.platform.tracing.otel.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ValidationModePolicyValidator}.
 *
 * <p>The validator operates on an already structurally-validated,
 * normalised payload (i.e. the output of
 * {@code TracingControlProtocolDecoder}).  Therefore every test payload
 * here uses the correct Java types ({@code String}, {@code Boolean}) that
 * the decoder would have placed into the normalised map.
 *
 * <p>Tests are grouped by rule:
 * <ul>
 *   <li>Rule 1 – allowed-value check for {@code validation.mode}</li>
 *   <li>Rule 2 – cross-field consistency between {@code validation.mode}
 *       and {@code validation.strict}</li>
 * </ul>
 */
class ValidationModePolicyValidatorTest {

    // =========================================================================
    // Happy-path: both fields absent
    // =========================================================================

    @Test
    void acceptsPayloadWithNoValidationFields() {
        TracingControlDomainValidationResult result =
                ValidationModePolicyValidator.validate(Map.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    // =========================================================================
    // Rule 1 – allowed-value check (mode present, strict absent)
    // =========================================================================

    @Test
    void acceptsLogOnlyModeAlone() {
        TracingControlDomainValidationResult result =
                ValidationModePolicyValidator.validate(
                        Map.of(TracingControlProtocolKeys.VALIDATION_MODE,
                               ValidationModePolicyValidator.MODE_LOG_ONLY));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsStrictModeAlone() {
        TracingControlDomainValidationResult result =
                ValidationModePolicyValidator.validate(
                        Map.of(TracingControlProtocolKeys.VALIDATION_MODE,
                               ValidationModePolicyValidator.MODE_STRICT));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsUnknownModeValue() {
        TracingControlDomainValidationResult result =
                ValidationModePolicyValidator.validate(
                        Map.of(TracingControlProtocolKeys.VALIDATION_MODE, "SILENT"));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst())
                .contains("SILENT")
                .contains("not a recognised mode");
    }

    @Test
    void rejectsBlankModeValue() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "   ");

        TracingControlDomainValidationResult result =
                ValidationModePolicyValidator.validate(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst()).contains("must not be blank");
    }

    // =========================================================================
    // Rule 1 – strict present alone (no mode): always accepted
    // =========================================================================

    @Test
    void acceptsStrictBooleanAloneWithoutMode() {
        TracingControlDomainValidationResult result =
                ValidationModePolicyValidator.validate(
                        Map.of(TracingControlProtocolKeys.VALIDATION_STRICT, true));

        assertThat(result.valid()).isTrue();
    }

    // =========================================================================
    // Rule 2 – cross-field consistency
    // =========================================================================

    @ParameterizedTest(name = "mode={0} strict={1} → valid")
    @CsvSource({
            "LOG_ONLY, false",
            "STRICT,   true"
    })
    void acceptsConsistentModePairs(String mode, boolean strict) {
        TracingControlDomainValidationResult result =
                ValidationModePolicyValidator.validate(
                        Map.of(TracingControlProtocolKeys.VALIDATION_MODE,   mode,
                               TracingControlProtocolKeys.VALIDATION_STRICT, strict));

        assertThat(result.valid()).isTrue();
    }

    @ParameterizedTest(name = "mode={0} strict={1} → conflict")
    @CsvSource({
            "LOG_ONLY, true",
            "STRICT,   false"
    })
    void rejectsConflictingModePairs(String mode, boolean strict) {
        TracingControlDomainValidationResult result =
                ValidationModePolicyValidator.validate(
                        Map.of(TracingControlProtocolKeys.VALIDATION_MODE,   mode,
                               TracingControlProtocolKeys.VALIDATION_STRICT, strict));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst())
                .contains("conflicts with")
                .contains(mode);
    }

    // =========================================================================
    // Rule 2 – unknown mode does not produce a cross-field violation
    //          (only the allowed-value violation is reported)
    // =========================================================================

    @Test
    void unknownModeWithStrictProducesOnlyAllowedValueViolation() {
        TracingControlDomainValidationResult result =
                ValidationModePolicyValidator.validate(
                        Map.of(TracingControlProtocolKeys.VALIDATION_MODE,   "UNKNOWN",
                               TracingControlProtocolKeys.VALIDATION_STRICT, true));

        assertThat(result.valid()).isFalse();
        // exactly one violation: the allowed-value rule; no piling-on from cross-field rule
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst()).contains("not a recognised mode");
    }

    // =========================================================================
    // Interaction with sampling violations in RuntimePolicyControlDomainValidator
    // =========================================================================

    @Test
    void runtimeValidatorMergesValidationModeViolationsWithSamplingViolations() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.5d);           // sampling violation
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "LOG_ONLY");
        payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);        // cross-field violation

        TracingControlDomainValidationResult result =
                RuntimePolicyControlDomainValidator.validate(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(2);                              // both violations surfaced
    }
}
