package space.br1440.platform.tracing.otel.sampling.policy;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

import java.util.Set;
import java.util.Map;

final class ForceHeaderPolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision FORCE = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.FORCE_HEADER, SamplingPolicyRuleNames.FORCE_HEADER
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.FORCE_HEADER;
    }

    @Override
    public SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            boolean enabled,
            Map<String, Double> routeRatios,
            double defaultRatio,
            SamplingPolicySnapshot startupRules) {
        if (startupRules.getForceRecordValues().isEmpty()) {
            return null;
        }

        if (matchesForceValue(
                startupRules.getForceRecordValues(),
                request.forceTraceHeaderValue())) {
            return FORCE;
        }

        return null;
    }

    private static boolean matchesForceValue(Set<String> normalizedValues, String rawValue) {
        if (rawValue == null || rawValue.isEmpty()
                || normalizedValues == null || normalizedValues.isEmpty()) {
            return false;
        }

        if (normalizedValues.contains(rawValue)) {
            return true;
        }

        for (String normalized : normalizedValues) {
            if (normalized.equalsIgnoreCase(rawValue)) {
                return true;
            }
        }

        return false;
    }
}
