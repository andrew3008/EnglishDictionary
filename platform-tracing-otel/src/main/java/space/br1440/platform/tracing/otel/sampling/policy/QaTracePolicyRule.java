package space.br1440.platform.tracing.otel.sampling.policy;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

final class QaTracePolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision QA = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.QA_TRACE, SamplingPolicyRuleNames.QA_TRACE
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.QA_TRACE;
    }

    @Override
    public SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot) {
        if (request.qaTrace()) {
            return QA;
        }

        return null;
    }
}
