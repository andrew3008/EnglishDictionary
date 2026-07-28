package space.br1440.platform.tracing.otel.sampling.policy;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

import java.util.Map;

final class QaTracePolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision QA = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.QA_TRACE, SamplingPolicyRuleNames.QA_TRACE
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.QA_TRACE;
    }

    @Override
    public SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            boolean enabled,
            Map<String, Double> routeRatios,
            double defaultRatio,
            SamplingPolicySnapshot startupRules) {
        if (request.qaTrace()) {
            return QA;
        }

        return null;
    }
}
