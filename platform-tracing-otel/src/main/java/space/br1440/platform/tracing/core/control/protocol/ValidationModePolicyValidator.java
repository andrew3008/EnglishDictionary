package space.br1440.platform.tracing.core.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain validator for validation-mode related fields in the normalised
 * control-protocol payload.
 *
 * <p>Structural validation (type, unknown keys) is already performed by
 * {@code TracingControlProtocolDecoder} at the API layer.  This validator
 * enforces two <em>domain / policy</em> rules that require knowledge of
 * the business semantics of {@code ValidationSnapshot}:
 *
 * <ol>
 *   <li><b>Allowed-value rule</b> – when {@code validation.mode} is
 *       present, its value must be one of the recognised mode tokens
 *       ({@code LOG_ONLY} or {@code STRICT}).  Any other string is
 *       rejected as an unknown validation mode.</li>
 *   <li><b>Cross-field consistency rule</b> – when both
 *       {@code validation.mode} and {@code validation.strict} are present
 *       in the same payload they must agree:
 *       {@code LOG_ONLY} implies {@code strict=false} and
 *       {@code STRICT} implies {@code strict=true}.
 *       Disagreement is rejected as a policy conflict, because applying
 *       such a payload would produce an ambiguous {@code ValidationSnapshot}
 *       state.</li>
 * </ol>
 *
 * <p>The mode tokens deliberately mirror the two meaningful states of
 * {@link space.br1440.platform.tracing.core.validation.ValidationSnapshot}:
 * <ul>
 *   <li>{@code LOG_ONLY}  → {@code enabled=true, strict=false}</li>
 *   <li>{@code STRICT}    → {@code enabled=true, strict=true}</li>
 * </ul>
 * Whether validation itself is enabled at all is expressed by the
 * separate {@code validation.enabled} boolean key and is not validated
 * here.
 */
public final class ValidationModePolicyValidator {

    /** Recognised values for the {@code validation.mode} wire key. */
    public static final String MODE_LOG_ONLY = "LOG_ONLY";
    public static final String MODE_STRICT   = "STRICT";

    static final Set<String> ALLOWED_MODES = Set.of(MODE_LOG_ONLY, MODE_STRICT);

    private ValidationModePolicyValidator() {
    }

    /**
     * Validates validation-mode policy rules against an already
     * structurally-validated, normalised payload produced by
     * {@code TracingControlProtocolDecoder}.
     *
     * @param normalizedPayload immutable map of normalised wire values
     * @return result carrying {@code valid=true} when no violations are
     *         found, or {@code valid=false} with a non-empty violations
     *         list otherwise
     */
    public static TracingControlDomainValidationResult validate(Map<String, Object> normalizedPayload) {
        List<String> violations = new ArrayList<>();

        String mode   = modeValue(normalizedPayload);
        Boolean strict = strictValue(normalizedPayload);

        validateModeAllowedValue(mode, violations);
        validateCrossFieldConsistency(mode, strict, violations);

        if (violations.isEmpty()) {
            return TracingControlDomainValidationResult.success();
        }
        return new TracingControlDomainValidationResult(false, violations);
    }

    // -------------------------------------------------------------------------
    // Rule 1 – allowed-value check
    // -------------------------------------------------------------------------

    private static void validateModeAllowedValue(String mode, List<String> violations) {
        if (mode == null) {
            return; // field absent – structural layer already accepted this
        }
        if (mode.isBlank()) {
            violations.add(
                "validation.mode must not be blank; allowed values: " + ALLOWED_MODES);
            return;
        }
        if (!ALLOWED_MODES.contains(mode)) {
            violations.add(
                "validation.mode '" + mode + "' is not a recognised mode; "
                + "allowed values: " + ALLOWED_MODES);
        }
    }

    // -------------------------------------------------------------------------
    // Rule 2 – cross-field consistency
    // -------------------------------------------------------------------------

    private static void validateCrossFieldConsistency(String mode,
                                                      Boolean strict,
                                                      List<String> violations) {
        if (mode == null || strict == null) {
            return; // one or both absent – no conflict possible
        }
        if (!ALLOWED_MODES.contains(mode)) {
            return; // mode already rejected above; don't pile on
        }

        boolean modeImpliesStrict = MODE_STRICT.equals(mode);
        if (modeImpliesStrict != strict) {
            violations.add(
                "validation.mode '" + mode + "' conflicts with "
                + "validation.strict=" + strict
                + "; LOG_ONLY requires strict=false, STRICT requires strict=true");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String modeValue(Map<String, Object> payload) {
        Object raw = payload.get(TracingControlProtocolKeys.VALIDATION_MODE);
        return raw instanceof String s ? s : null;
    }

    private static Boolean strictValue(Map<String, Object> payload) {
        Object raw = payload.get(TracingControlProtocolKeys.VALIDATION_STRICT);
        return raw instanceof Boolean b ? b : null;
    }
}
