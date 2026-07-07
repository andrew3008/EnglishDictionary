package space.br1440.platform.tracing.core.sampling.policy;

import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;

final class DefaultRatioPolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision SAMPLE = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.DEFAULT_RATIO, SamplingPolicyRuleNames.DEFAULT_RATIO
    );

    private static final SamplingPolicyDecision DROP = SamplingPolicyDecision.drop(
            SamplingPolicyReason.DEFAULT_RATIO_DROP, SamplingPolicyRuleNames.DEFAULT_RATIO
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.DEFAULT_RATIO;
    }

    @Override
    public SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot) {
        double ratio = snapshot.getDefaultRatio();
        if (ratio >= 1.0) {
            return SAMPLE;
        }

        if (ratio <= 0.0) {
            return DROP;
        }

        if (TraceIdRatioDecision.shouldSample(request.traceId(), ratio)) {
            return SAMPLE;
        }

        return DROP;
    }
}
