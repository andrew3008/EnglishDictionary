package space.br1440.platform.tracing.otel.sampling.policy;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

import java.util.Map;

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
    public SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            boolean enabled,
            Map<String, Double> routeRatios,
            double defaultRatio,
            SamplingPolicySnapshot startupRules) {
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
