package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;

/**
 * Полный immutable snapshot восьми runtime-mutable параметров Category A.
 */
record EffectiveTracingPolicy(
        boolean samplingEnabled,
        double samplingRatio,
        NavigableMap<String, Double> samplingRouteRatios,
        boolean validationEnabled,
        boolean validationStrict,
        boolean exporterEnabled,
        boolean traceControlHeadersEnabled,
        PolicyLogLevel diagnosticsLogLevel) {

    EffectiveTracingPolicy {
        samplingRatio = EffectiveTracingPolicyValidator.normalizeRatio(
                samplingRatio,
                "samplingRatio");
        samplingRouteRatios = EffectiveTracingPolicyValidator.normalizeRouteRatios(
                samplingRouteRatios);
        Objects.requireNonNull(diagnosticsLogLevel, "diagnosticsLogLevel");
    }

    static EffectiveTracingPolicy of(
            boolean samplingEnabled,
            double samplingRatio,
            Map<String, Double> samplingRouteRatios,
            boolean validationEnabled,
            boolean validationStrict,
            boolean exporterEnabled,
            boolean traceControlHeadersEnabled,
            PolicyLogLevel diagnosticsLogLevel) {
        return new EffectiveTracingPolicy(
                samplingEnabled,
                samplingRatio,
                EffectiveTracingPolicyValidator.normalizeRouteRatios(samplingRouteRatios),
                validationEnabled,
                validationStrict,
                exporterEnabled,
                traceControlHeadersEnabled,
                diagnosticsLogLevel);
    }
}
