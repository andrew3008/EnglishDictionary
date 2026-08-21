package space.br1440.platform.tracing.test.harness;

import java.util.List;
import java.util.Map;

import space.br1440.platform.common.util.StringUtils;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;

public record SamplingDecisionCase(String caseId,
                                   boolean enabled,
                                   List<String> droppedRoutes,
                                   List<String> forceRecordValues,
                                   Map<String, Double> routeRatios,
                                   double defaultRatio,
                                   Context parentContext,
                                   String urlPath,
                                   String traceId,
                                   SamplingDecision expectedDecision,
                                   String expectedReason,
                                   String expectedWinningRule,
                                   String expectedMetricReasonCode) {

    public SamplingDecisionCase {
        if (StringUtils.isNullOrBlank(caseId)) {
            throw new IllegalArgumentException("caseId required");
        }

        droppedRoutes = (droppedRoutes == null) ? List.of() : List.copyOf(droppedRoutes);
        forceRecordValues = (forceRecordValues == null) ? List.of() : List.copyOf(forceRecordValues);
        routeRatios = (routeRatios == null) ? Map.of() : Map.copyOf(routeRatios);
        parentContext = (parentContext == null) ? Context.root() : parentContext;
        traceId = (traceId == null) ? SamplerHarness.DEFAULT_TRACE_ID : traceId;
    }
}
