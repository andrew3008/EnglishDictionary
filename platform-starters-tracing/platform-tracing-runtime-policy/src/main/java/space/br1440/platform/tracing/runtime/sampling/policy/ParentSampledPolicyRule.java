package space.br1440.platform.tracing.runtime.sampling.policy;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;

final class ParentSampledPolicyRule {

    private static final SamplingPolicyDecision PARENT_SAMPLED = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.PARENT_DECISION, SamplingPolicyRuleNames.PARENT_DECISION
    );

    private static final SamplingPolicyDecision PARENT_NOT_SAMPLED = SamplingPolicyDecision.drop(
            SamplingPolicyReason.PARENT_DROP, SamplingPolicyRuleNames.PARENT_DECISION
    );

    SamplingPolicyDecision evaluate(SamplingPolicyRequest request) {
        return switch (request.parentContextState()) {
            case SAMPLED -> PARENT_SAMPLED;
            case NOT_SAMPLED -> PARENT_NOT_SAMPLED;
            case ABSENT -> null;
        };
    }
}
