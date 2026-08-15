package space.br1440.platform.tracing.otel.sampling.policy;

import java.util.Map;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

final class HardDropPolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision DROP = SamplingPolicyDecision.drop(
            SamplingPolicyReason.HARD_DROP, SamplingPolicyRuleNames.HARD_DROP
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.HARD_DROP;
    }

    @Override
    public SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            boolean enabled,
            Map<String, Double> routeRatios,
            double defaultRatio,
            SamplingPolicySnapshot startupRules) {
        if (startupRules.getDroppedRoutes().isEmpty()) {
            return null;
        }

        String urlPath = request.urlPath();
        if (StringUtils.isNullOrEmpty(urlPath)) {
            return null;
        }

        for (String prefix : startupRules.getDroppedRoutes()) {
            if (urlPath.startsWith(prefix)) {
                return DROP;
            }
        }

        return null;
    }
}
