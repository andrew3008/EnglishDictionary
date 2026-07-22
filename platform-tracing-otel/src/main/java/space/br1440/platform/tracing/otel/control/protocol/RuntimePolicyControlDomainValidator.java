package space.br1440.platform.tracing.otel.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicyPropertiesValidator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain validator for {@code APPLY_RUNTIME_POLICY} /
 * {@code VALIDATE_RUNTIME_POLICY} payloads.
 *
 * <p>Operates on the <em>normalised</em> payload produced by
 * {@code TracingControlProtocolDecoder} (structural and open-type
 * validation is already complete at that point).  This class enforces
 * two categories of domain / policy rules:
 *
 * <ul>
 *   <li><b>Sampling bounds</b> – delegates to
 *       {@link SamplingPolicyPropertiesValidator} for ratio ranges,
 *       drop-path constraints and force-header-value limits.</li>
 *   <li><b>Validation-mode policy</b> – delegates to
 *       {@link ValidationModePolicyValidator} for the allowed-value and
 *       cross-field consistency rules on {@code validation.mode} /
 *       {@code validation.strict}.</li>
 * </ul>
 *
 * <p>All violations from every sub-validator are collected and returned
 * together so that callers receive a complete diagnostic picture in a
 * single pass, rather than failing on the first error found.
 */
public final class RuntimePolicyControlDomainValidator {

    private static final Set<String> RUNTIME_POLICY_KEYS = Set.of(
            TracingControlProtocolKeys.SAMPLING_RATIO,
            TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS,
            TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED,
            TracingControlProtocolKeys.SAMPLING_QA_TRACE_ENABLED,
            TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_ENABLED,
            TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES,
            TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES,
            TracingControlProtocolKeys.SCRUBBING_ENABLED,
            TracingControlProtocolKeys.SCRUBBING_MODE,
            TracingControlProtocolKeys.SCRUBBING_RULE_NAMES,
            TracingControlProtocolKeys.VALIDATION_ENABLED,
            TracingControlProtocolKeys.VALIDATION_MODE,
            TracingControlProtocolKeys.VALIDATION_STRICT,
            TracingControlProtocolKeys.ENRICHING_ENABLED,
            TracingControlProtocolKeys.EXPORT_ENABLED,
            TracingControlProtocolKeys.PROPAGATION_ENABLED);

    private RuntimePolicyControlDomainValidator() {
    }

    /**
     * Validates domain-level rules against a normalised payload.
     *
     * @param normalizedPayload normalised map produced by the decoder
     * @return {@code valid=true} when no violations are found;
     *         {@code valid=false} with a non-empty violations list otherwise
     */
    public static TracingControlDomainValidationResult validate(Map<String, Object> normalizedPayload) {
        List<String> violations = new ArrayList<>();

        collectEmptyMutationViolation(normalizedPayload, violations);
        collectSamplingViolations(normalizedPayload, violations);
        collectValidationModeViolations(normalizedPayload, violations);

        if (violations.isEmpty()) {
            return TracingControlDomainValidationResult.success();
        }
        return new TracingControlDomainValidationResult(false, violations);
    }

    private static void collectEmptyMutationViolation(Map<String, Object> payload,
                                                      List<String> violations) {
        if (!isApplyOperation(payload)) {
            return;
        }
        boolean hasMutation = payload.keySet().stream().anyMatch(RUNTIME_POLICY_KEYS::contains);
        if (!hasMutation) {
            violations.add("empty mutation rejected");
        }
    }

    private static boolean isApplyOperation(Map<String, Object> payload) {
        return TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue()
                .equals(payload.get(TracingControlProtocolKeys.OPERATION));
    }

    // -------------------------------------------------------------------------
    // Sampling bounds
    // -------------------------------------------------------------------------

    private static void collectSamplingViolations(Map<String, Object> payload,
                                                  List<String> violations) {
        try {
            SamplingPolicyPropertiesValidator.validate(toSamplingPolicyProperties(payload));
        } catch (IllegalArgumentException ex) {
            violations.add(ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static SamplingPolicyProperties toSamplingPolicyProperties(Map<String, Object> payload) {
        double defaultRatio = valueOrDefault(payload.get(TracingControlProtocolKeys.SAMPLING_RATIO), 1.0d);
        String[] droppedRoutes = (String[]) payload.get(TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES);
        String[] forceValues   = (String[]) payload.get(TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES);
        Map<String, Double> routeRatios =
                (Map<String, Double>) payload.get(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS);

        return new SamplingPolicyProperties(
                true,
                defaultRatio,
                droppedRoutes == null ? null : Arrays.asList(droppedRoutes),
                forceValues   == null ? null : Set.copyOf(Arrays.asList(forceValues)),
                routeRatios   == null ? null : new LinkedHashMap<>(routeRatios));
    }

    private static double valueOrDefault(Object value, double fallback) {
        return value instanceof Double d ? d : fallback;
    }

    // -------------------------------------------------------------------------
    // Validation-mode policy
    // -------------------------------------------------------------------------

    private static void collectValidationModeViolations(Map<String, Object> payload,
                                                        List<String> violations) {
        TracingControlDomainValidationResult modeResult =
                ValidationModePolicyValidator.validate(payload);
        if (!modeResult.valid()) {
            violations.addAll(modeResult.violations());
        }
    }
}
