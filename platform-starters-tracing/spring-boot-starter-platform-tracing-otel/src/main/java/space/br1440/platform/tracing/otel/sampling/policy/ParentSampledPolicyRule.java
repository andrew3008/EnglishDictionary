package space.br1440.platform.tracing.otel.sampling.policy;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

import java.util.Map;

final class ParentSampledPolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision PARENT_SAMPLED = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.PARENT_DECISION, SamplingPolicyRuleNames.PARENT_DECISION
    );

    private static final SamplingPolicyDecision PARENT_NOT_SAMPLED = SamplingPolicyDecision.drop(
            SamplingPolicyReason.PARENT_DROP, SamplingPolicyRuleNames.PARENT_DECISION
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.PARENT_DECISION;
    }

    @Override
    public SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            boolean enabled,
            Map<String, Double> routeRatios,
            double defaultRatio,
            SamplingPolicySnapshot startupRules) {
        return switch (request.parentContextState()) {
            case SAMPLED -> PARENT_SAMPLED;
            case NOT_SAMPLED -> PARENT_NOT_SAMPLED;
            case ABSENT -> null;
        };
    }
}
