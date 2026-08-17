package space.br1440.platform.tracing.runtime.sampling.policy;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;

final class QaTracePolicyRule {

    private static final SamplingPolicyDecision QA = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.QA_TRACE, SamplingPolicyRuleNames.QA_TRACE
    );

    SamplingPolicyDecision evaluate(SamplingPolicyRequest request) {
        if (request.qaTrace()) {
            return QA;
        }

        return null;
    }
}
