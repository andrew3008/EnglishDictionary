package space.br1440.platform.tracing.otel.javaagent.control.settings.model;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public record TracingSettings(boolean samplingEnabled,
                              double samplingRatio,
                              NavigableMap<String, Double> samplingRouteRatios,
                              boolean validationEnabled,
                              boolean validationStrict,
                              boolean exporterEnabled,
                              boolean traceControlHeadersEnabled,
                              TracingLogLevel diagnosticsLogLevel) {

    public static final int MAX_ROUTE_ENTRIES = 100;

    private static final int MAX_ROUTE_PREFIX_LENGTH = 255;

    public TracingSettings {
        samplingRatio = normalizeRatio(samplingRatio, "samplingRatio");
        samplingRouteRatios = normalizeRouteRatios(samplingRouteRatios);
        Objects.requireNonNull(diagnosticsLogLevel, "diagnosticsLogLevel");
    }

    public static TracingSettings of(boolean samplingEnabled,
                                     double samplingRatio,
                                     Map<String, Double> samplingRouteRatios,
                                     boolean validationEnabled,
                                     boolean validationStrict,
                                     boolean exporterEnabled,
                                     boolean traceControlHeadersEnabled,
                                     TracingLogLevel diagnosticsLogLevel) {
        return new TracingSettings(
                samplingEnabled,
                samplingRatio,
                normalizeRouteRatios(samplingRouteRatios),
                validationEnabled,
                validationStrict,
                exporterEnabled,
                traceControlHeadersEnabled,
                diagnosticsLogLevel
        );
    }

    private static double normalizeRatio(double ratio, String fieldName) {
        if (!Double.isFinite(ratio) || ratio < 0.0d || ratio > 1.0d) {
            throw new IllegalArgumentException(
                    "%s must be finite and within [0.0, 1.0], but was %s"
                            .formatted(fieldName, ratio)
            );
        }

        return (ratio == 0.0d) ? 0.0d : ratio;
    }

    private static NavigableMap<String, Double> normalizeRouteRatios(Map<String, Double> routeRatios) {
        Objects.requireNonNull(routeRatios, "routeRatios");
        if (routeRatios.size() > MAX_ROUTE_ENTRIES) {
            throw new IllegalArgumentException(
                    "routeRatios must contain at most %d entries, but had %d"
                            .formatted(MAX_ROUTE_ENTRIES, routeRatios.size())
            );
        }

        NavigableMap<String, Double> normalized = new TreeMap<>();
        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            String routePrefix = requireRoutePrefix(entry.getKey());
            Double ratio = Objects.requireNonNull(entry.getValue(), "route ratio");
            normalized.put(
                    routePrefix,
                    normalizeRatio(ratio, "route ratio for %s".formatted(routePrefix))
            );
        }

        return Collections.unmodifiableNavigableMap(normalized);
    }

    private static String requireRoutePrefix(String routePrefix) {
        Objects.requireNonNull(routePrefix, "routePrefix");

        if (routePrefix.isBlank()) {
            throw new IllegalArgumentException("routePrefix must not be blank");
        }

        if (routePrefix.length() > MAX_ROUTE_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "routePrefix must contain at most %d UTF-16 code units, but had %d"
                            .formatted(MAX_ROUTE_PREFIX_LENGTH, routePrefix.length())
            );
        }

        if (routePrefix.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("routePrefix must not contain ISO control characters");
        }

        return routePrefix;
    }
}
