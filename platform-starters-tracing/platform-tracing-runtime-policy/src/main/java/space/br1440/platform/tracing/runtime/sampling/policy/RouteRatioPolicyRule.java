package space.br1440.platform.tracing.runtime.sampling.policy;

import java.util.Map;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;

final class RouteRatioPolicyRule {

    private static final SamplingPolicyDecision SAMPLE = SamplingPolicyDecision.recordAndSample(
            SamplingPolicyReason.ROUTE_RATIO, SamplingPolicyRuleNames.ROUTE_RATIO
    );

    private static final SamplingPolicyDecision DROP = SamplingPolicyDecision.drop(
            SamplingPolicyReason.ROUTE_RATIO_DROP, SamplingPolicyRuleNames.ROUTE_RATIO
    );

    SamplingPolicyDecision evaluate(SamplingPolicyRequest request, Map<String, Double> routeRatios) {
        if (routeRatios.isEmpty()) {
            return null;
        }

        String urlPath = request.urlPath();
        if (StringUtils.isNullOrEmpty(urlPath)) {
            return null;
        }

        Double selectedRatio = findBestMatchingRatio(urlPath, routeRatios);
        if (selectedRatio == null) {
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

    private static Double findBestMatchingRatio(String urlPath, Map<String, Double> routeRatios) {
        String selectedPrefix = null;
        Double selectedRatio = null;
        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            String candidatePrefix = entry.getKey();
            if (isBetterMatchingPrefix(urlPath, candidatePrefix, selectedPrefix)) {
                selectedPrefix = candidatePrefix;
                selectedRatio = entry.getValue();
            }
        }

        return selectedRatio;
    }

    private static boolean isBetterMatchingPrefix(String urlPath, String candidatePrefix, String selectedPrefix) {
        if (!urlPath.startsWith(candidatePrefix)) {
            return false;
        }

        if (selectedPrefix == null) {
            return true;
        }

        int lengthComparison = Integer.compare(candidatePrefix.length(), selectedPrefix.length());
        return (lengthComparison > 0)
                || (lengthComparison == 0) && (candidatePrefix.compareTo(selectedPrefix) < 0);
    }
}
