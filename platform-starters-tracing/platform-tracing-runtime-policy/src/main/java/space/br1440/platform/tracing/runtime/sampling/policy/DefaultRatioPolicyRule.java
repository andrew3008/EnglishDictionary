package space.br1440.platform.tracing.runtime.sampling.policy;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;

final class DefaultRatioPolicyRule {

    private static final SamplingPolicyDecision SAMPLE = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.DEFAULT_RATIO, SamplingPolicyRuleNames.DEFAULT_RATIO
    );

    private static final SamplingPolicyDecision DROP = SamplingPolicyDecision.drop(
            SamplingPolicyReason.DEFAULT_RATIO_DROP, SamplingPolicyRuleNames.DEFAULT_RATIO
    );

    SamplingPolicyDecision evaluate(SamplingPolicyRequest request, double defaultRatio) {
        if (defaultRatio >= 1.0) {
            return SAMPLE;
        }

        if (defaultRatio <= 0.0) {
            return DROP;
        }

        if (TraceIdRatioDecision.shouldSample(request.traceId(), defaultRatio)) {
            return SAMPLE;
        }

        return DROP;
    }
}
