package space.br1440.platform.tracing.core.sampling.policy;

import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;

import java.util.Set;

final class ForceHeaderPolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision FORCE = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.FORCE_HEADER, SamplingPolicyRuleNames.FORCE_HEADER
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.FORCE_HEADER;
    }

    @Override
    public SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot) {
        if (snapshot.getForceRecordValues().isEmpty()) {
            return null;
        }

        if (matchesForceValue(snapshot.getForceRecordValues(), request.forceTraceHeaderValue())) {
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
