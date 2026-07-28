package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

record CompleteTracingPolicySnapshot(
        boolean samplingEnabled,
        double samplingRatio,
        Map<String, Double> samplingRouteRatios,
        boolean validationEnabled,
        boolean validationStrict,
        boolean exporterEnabled,
        boolean traceControlHeadersEnabled,
        OtelAgentLogLevel diagnosticsLogLevel) {

    static final int MAX_ROUTE_ENTRIES = 100;
    static final int MAX_ROUTE_PREFIX_LENGTH = 255;

    CompleteTracingPolicySnapshot {
        samplingRatio = normalizedRatio(samplingRatio, "samplingRatio");
        Objects.requireNonNull(samplingRouteRatios, "samplingRouteRatios");
        if (samplingRouteRatios.size() > MAX_ROUTE_ENTRIES) {
            throw new IllegalArgumentException("samplingRouteRatios exceeds 100 entries");
        }
        Map<String, Double> normalizedRoutes = new TreeMap<>();
        samplingRouteRatios.forEach((route, ratio) -> {
            String normalizedRoute = requireRoute(route);
            Double previous = normalizedRoutes.put(
                    normalizedRoute,
                    normalizedRatio(Objects.requireNonNull(ratio, "route ratio"), "route ratio"));
            if (previous != null) {
                throw new IllegalArgumentException("duplicate normalized route: " + normalizedRoute);
            }
        });
        samplingRouteRatios = Collections.unmodifiableMap(
                new LinkedHashMap<>(normalizedRoutes));
        diagnosticsLogLevel = Objects.requireNonNull(
                diagnosticsLogLevel,
                "diagnosticsLogLevel");
    }

    CompleteTracingPolicySnapshot withSamplingEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(
                value,
                samplingRatio,
                samplingRouteRatios,
                validationEnabled,
                validationStrict,
                exporterEnabled,
                traceControlHeadersEnabled,
                diagnosticsLogLevel);
    }

    CompleteTracingPolicySnapshot withSamplingRatio(double value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled,
                value,
                samplingRouteRatios,
                validationEnabled,
                validationStrict,
                exporterEnabled,
                traceControlHeadersEnabled,
                diagnosticsLogLevel);
    }

    CompleteTracingPolicySnapshot withSamplingRouteRatios(Map<String, Double> value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled,
                samplingRatio,
                value,
                validationEnabled,
                validationStrict,
                exporterEnabled,
                traceControlHeadersEnabled,
                diagnosticsLogLevel);
    }

    CompleteTracingPolicySnapshot withValidationEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled,
                samplingRatio,
                samplingRouteRatios,
                value,
                validationStrict,
                exporterEnabled,
                traceControlHeadersEnabled,
                diagnosticsLogLevel);
    }

    CompleteTracingPolicySnapshot withValidationStrict(boolean value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled,
                samplingRatio,
                samplingRouteRatios,
                validationEnabled,
                value,
                exporterEnabled,
                traceControlHeadersEnabled,
                diagnosticsLogLevel);
    }

    CompleteTracingPolicySnapshot withExporterEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled,
                samplingRatio,
                samplingRouteRatios,
                validationEnabled,
                validationStrict,
                value,
                traceControlHeadersEnabled,
                diagnosticsLogLevel);
    }

    CompleteTracingPolicySnapshot withTraceControlHeadersEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled,
                samplingRatio,
                samplingRouteRatios,
                validationEnabled,
                validationStrict,
                exporterEnabled,
                value,
                diagnosticsLogLevel);
    }

    CompleteTracingPolicySnapshot withDiagnosticsLogLevel(OtelAgentLogLevel value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled,
                samplingRatio,
                samplingRouteRatios,
                validationEnabled,
                validationStrict,
                exporterEnabled,
                traceControlHeadersEnabled,
                value);
    }

    private static double normalizedRatio(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(field + " must be finite and within [0.0, 1.0]");
        }
        return value == 0.0d ? 0.0d : value;
    }

    private static String requireRoute(String route) {
        Objects.requireNonNull(route, "route");
        if (route.isEmpty() || route.length() > MAX_ROUTE_PREFIX_LENGTH) {
            throw new IllegalArgumentException("route length must be within [1, 255]");
        }
        if (route.chars().anyMatch(character -> character == 0 || Character.isISOControl(character))) {
            throw new IllegalArgumentException("route contains a control character");
        }
        return route;
    }
}
