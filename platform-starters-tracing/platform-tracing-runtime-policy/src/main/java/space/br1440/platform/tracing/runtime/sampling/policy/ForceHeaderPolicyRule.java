package space.br1440.platform.tracing.runtime.sampling.policy;

import java.util.Set;

import space.br1440.platform.common.util.SetUtils;
import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;

final class ForceHeaderPolicyRule {

    private static final SamplingPolicyDecision FORCE = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.FORCE_HEADER, SamplingPolicyRuleNames.FORCE_HEADER
    );

    SamplingPolicyDecision evaluate(SamplingPolicyRequest request, Set<String> forceRecordValues) {
        if (forceRecordValues.isEmpty()) {
            return null;
        }

        if (matchesForceValue(
                forceRecordValues,
                request.forceTraceHeaderValue()
        )) {
            return FORCE;
        }

        return null;
    }

    private static boolean matchesForceValue(Set<String> normalizedValues, String rawValue) {
        if (StringUtils.isNullOrBlank(rawValue) || SetUtils.isNullOrEmpty(normalizedValues)) {
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
