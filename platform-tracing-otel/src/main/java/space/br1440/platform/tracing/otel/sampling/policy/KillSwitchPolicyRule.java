package space.br1440.platform.tracing.otel.sampling.policy;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

import java.util.Map;

final class KillSwitchPolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision DROP = SamplingPolicyDecision.drop(
            SamplingPolicyReason.KILL_SWITCH, SamplingPolicyRuleNames.KILL_SWITCH
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.KILL_SWITCH;
    }

    @Override
    public SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            boolean enabled,
            Map<String, Double> routeRatios,
            double defaultRatio,
            SamplingPolicySnapshot startupRules) {
        if (!enabled) {
            return DROP;
        }

        return null;
    }
}
