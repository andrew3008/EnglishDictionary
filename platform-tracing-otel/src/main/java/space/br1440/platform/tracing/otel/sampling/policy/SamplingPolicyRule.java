package space.br1440.platform.tracing.otel.sampling.policy;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

import java.util.Map;

interface SamplingPolicyRule {

    String ruleName();

    default SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            SamplingPolicySnapshot startupRules) {
        return evaluate(
                request,
                startupRules == null || startupRules.isEnabled(),
                startupRules == null ? Map.of() : startupRules.getRouteRatioMap(),
                startupRules == null ? 1.0d : startupRules.getDefaultRatio(),
                startupRules);
    }

    SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            boolean enabled,
            Map<String, Double> routeRatios,
            double defaultRatio,
            SamplingPolicySnapshot startupRules);

}
