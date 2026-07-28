package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

final class EffectiveTracingPolicyValidator {

    static final int MAX_ROUTE_ENTRIES = 100;
    static final int MAX_ROUTE_PREFIX_LENGTH = 255;

    private EffectiveTracingPolicyValidator() {
    }

    static double normalizeRatio(double ratio, String fieldName) {
        if (!Double.isFinite(ratio) || ratio < 0.0d || ratio > 1.0d) {
            throw new IllegalArgumentException(
                    fieldName + " must be finite and within [0.0, 1.0], but was " + ratio);
        }
        return ratio == 0.0d ? 0.0d : ratio;
    }

    static NavigableMap<String, Double> normalizeRouteRatios(Map<String, Double> routeRatios) {
        Objects.requireNonNull(routeRatios, "routeRatios");
        if (routeRatios.size() > MAX_ROUTE_ENTRIES) {
            throw new IllegalArgumentException(
                    "routeRatios must contain at most " + MAX_ROUTE_ENTRIES + " entries");
        }

        NavigableMap<String, Double> normalized = new TreeMap<>();
        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            String routePrefix = requireRoutePrefix(entry.getKey());
            Double ratio = Objects.requireNonNull(entry.getValue(), "route ratio");
            normalized.put(
                    routePrefix,
                    normalizeRatio(ratio, "route ratio for " + routePrefix));
        }
        return Collections.unmodifiableNavigableMap(normalized);
    }

    static PolicyValidationResult validate(
            EffectiveTracingPolicy policy,
            PolicyValidationConstraints constraints) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(constraints, "constraints");

        if (policy.validationStrict() && !constraints.strictRuntimeAllowed()) {
            return PolicyValidationResult.rejected(PolicyMessageCode.STRICT_RUNTIME_NOT_ALLOWED);
        }
        return PolicyValidationResult.accepted();
    }

    private static String requireRoutePrefix(String routePrefix) {
        Objects.requireNonNull(routePrefix, "routePrefix");
        if (routePrefix.isBlank()) {
            throw new IllegalArgumentException("routePrefix must not be blank");
        }
        if (routePrefix.length() > MAX_ROUTE_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "routePrefix must contain at most "
                            + MAX_ROUTE_PREFIX_LENGTH
                            + " UTF-16 code units");
        }
        if (routePrefix.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("routePrefix must not contain ISO control characters");
        }
        return routePrefix;
    }
}
