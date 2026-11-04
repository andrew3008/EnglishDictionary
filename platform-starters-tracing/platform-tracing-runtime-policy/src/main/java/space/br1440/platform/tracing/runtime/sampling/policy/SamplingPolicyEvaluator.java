package space.br1440.platform.tracing.runtime.sampling.policy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.runtime.sampling.model.ParentContextState;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingOutcome;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingStartupRules;

public final class SamplingPolicyEvaluator {

    private SamplingPolicyEvaluator() {
    }

    public static SamplingOutcome evaluate(SamplingPolicyRequest request,
                                           boolean enabled,
                                           Map<String, Double> routeRatios,
                                           double defaultRatio,
                                           SamplingStartupRules startupRules) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(routeRatios, "routeRatios");
        Objects.requireNonNull(startupRules, "startupRules");

        if (!enabled) {
            return SamplingOutcome.KILL_SWITCH;
        }

        String path = request.urlPath();
        if (!StringUtils.isNullOrEmpty(path)) {
            for (String prefix : startupRules.droppedRoutes()) {
                if (path.startsWith(prefix)) {
                    return SamplingOutcome.HARD_DROP;
                }
            }
        }

        if (matchesForceValue(startupRules.forceRecordValues(), request.forceTraceHeaderValue())) {
            return SamplingOutcome.FORCE_HEADER;
        }

        if (request.qaTrace()) {
            return SamplingOutcome.QA_TRACE;
        }

        // Платформенные overrides намеренно предшествуют решению родителя.
        ParentContextState parent = Objects.requireNonNull(request.parentContextState(), "parentContextState");
        if (parent == ParentContextState.SAMPLED) {
            return SamplingOutcome.PARENT_SAMPLED;
        }

        if (parent == ParentContextState.NOT_SAMPLED) {
            return SamplingOutcome.PARENT_DROP;
        }

        if (!StringUtils.isNullOrEmpty(path)) {
            Double ratio = longestPrefixRatio(path, routeRatios);
            if (ratio != null) {
                return TraceIdRatioDecision.shouldSample(request.traceId(), ratio)
                        ? SamplingOutcome.ROUTE_SAMPLE : SamplingOutcome.ROUTE_DROP;
            }
        }

        return TraceIdRatioDecision.shouldSample(request.traceId(), defaultRatio)
                ? SamplingOutcome.DEFAULT_SAMPLE : SamplingOutcome.DEFAULT_DROP;
    }

    private static boolean matchesForceValue(Set<String> values, String rawValue) {
        if (values.isEmpty() || StringUtils.isNullOrBlank(rawValue)) {
            return false;
        }

        if (values.contains(rawValue)) {
            return true;
        }

        for (String value : values) {
            if (value.equalsIgnoreCase(rawValue)) {
                return true;
            }
        }

        return false;
    }

    private static Double longestPrefixRatio(String path, Map<String, Double> routes) {
        int longest = -1;
        Double selected = null;
        for (Map.Entry<String, Double> entry : routes.entrySet()) {
            String prefix = entry.getKey();
            if ((selected == null || prefix.length() > longest) && path.startsWith(prefix)) {
                longest = prefix.length();
                selected = entry.getValue();
            }
        }

        return selected;
    }
}
