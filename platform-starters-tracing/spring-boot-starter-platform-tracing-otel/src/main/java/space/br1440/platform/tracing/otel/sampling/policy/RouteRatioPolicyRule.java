package space.br1440.platform.tracing.otel.sampling.policy;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;
import java.util.Map;

final class RouteRatioPolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision SAMPLE = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.ROUTE_RATIO, SamplingPolicyRuleNames.ROUTE_RATIO
    );

    private static final SamplingPolicyDecision DROP = SamplingPolicyDecision.drop(
            SamplingPolicyReason.ROUTE_RATIO_DROP, SamplingPolicyRuleNames.ROUTE_RATIO
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.ROUTE_RATIO;
    }

    @Override
    public SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            boolean enabled,
            Map<String, Double> routeRatios,
            double defaultRatio,
            SamplingPolicySnapshot startupRules) {
        if (routeRatios.isEmpty()) {
            return null;
        }

        String urlPath = request.urlPath();
        if (StringUtils.isNullOrEmpty(urlPath)) {
            return null;
        }

        String selectedPrefix = null;
        double selectedRatio = 0.0d;
        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            String prefix = entry.getKey();
            if (urlPath.startsWith(prefix)
                    && (selectedPrefix == null
                    || prefix.length() > selectedPrefix.length()
                    || (prefix.length() == selectedPrefix.length()
                    && prefix.compareTo(selectedPrefix) < 0))) {
                selectedPrefix = prefix;
                selectedRatio = entry.getValue();
            }
        }

        if (selectedPrefix == null) {
            return null;
        }
        if (selectedRatio >= 1.0d) {
            return SAMPLE;
        }
        if (selectedRatio <= 0.0d) {
            return DROP;
        }
        return TraceIdRatioDecision.shouldSample(request.traceId(), selectedRatio)
                ? SAMPLE
                : DROP;
    }
}
