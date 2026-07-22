package space.br1440.platform.tracing.otel.sampling.policy;

import space.br1440.platform.tracing.otel.sampling.model.*;

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
    public SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot) {
        RouteRatioPrefix[] routeRatios = snapshot.getRouteRatios();
        if (routeRatios.length == 0) {
            return null;
        }

        String urlPath = request.urlPath();
        if (urlPath == null || urlPath.isEmpty()) {
            return null;
        }

        for (RouteRatioPrefix entry : routeRatios) {
            if (urlPath.startsWith(entry.prefix())) {
                double ratio = entry.ratio();
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

        return null;
    }
}
