package space.br1440.platform.tracing.autoconfigure.control.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireUtf8Meter;

public final class CompleteTracingSettings {

    private final boolean samplingEnabled;
    private final double samplingRatio;
    private final Map<String, Double> samplingRouteRatios;
    private final String validationLevel;
    private final boolean exporterEnabled;
    private final boolean traceControlHeadersEnabled;
    private final String diagnosticsLogLevel;

    private CompleteTracingSettings(boolean samplingEnabled,
                                          double samplingRatio,
                                          Map<String, Double> samplingRouteRatios,
                                          String validationLevel,
                                          boolean exporterEnabled,
                                          boolean traceControlHeadersEnabled,
                                          String diagnosticsLogLevel) {
        Map<String, Double> copiedRoutes = copyRoutes(samplingRouteRatios);

        this.samplingEnabled = samplingEnabled;
        this.samplingRatio = canonicalizeRatio(samplingRatio);
        this.samplingRouteRatios = Collections.unmodifiableMap(canonicalizeRoutes(copiedRoutes));
        this.validationLevel = canonicalizeValidationLevel(validationLevel);
        this.exporterEnabled = exporterEnabled;
        this.traceControlHeadersEnabled = traceControlHeadersEnabled;
        this.diagnosticsLogLevel = canonicalizeLogLevel(diagnosticsLogLevel);
    }

    public static CompleteTracingSettings helmBootstrapDefaults() {
        return new CompleteTracingSettings(
                true, 1.0, Map.of(),
                "WARNING", true, true, "INFO"
        );
    }

    public boolean samplingEnabled() {
        return samplingEnabled;
    }

    public double samplingRatio() {
        return samplingRatio;
    }

    public Map<String, Double> samplingRouteRatios() {
        return samplingRouteRatios;
    }

    public String validationLevel() {
        return validationLevel;
    }

    public boolean exporterEnabled() {
        return exporterEnabled;
    }

    public boolean traceControlHeadersEnabled() {
        return traceControlHeadersEnabled;
    }

    public String diagnosticsLogLevel() {
        return diagnosticsLogLevel;
    }

    public CompleteTracingSettings withSamplingEnabled(boolean value) {
        return new CompleteTracingSettings(
                value, samplingRatio, samplingRouteRatios,
                validationLevel, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withSamplingRatio(double value) {
        return new CompleteTracingSettings(
                samplingEnabled, value, samplingRouteRatios,
                validationLevel, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withSamplingRouteRatios(Map<String, Double> value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, value,
                validationLevel, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withValidationLevel(String value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                value, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withExporterEnabled(boolean value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationLevel, value, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withTraceControlHeadersEnabled(boolean value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationLevel, exporterEnabled, value, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withDiagnosticsLogLevel(String value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationLevel, exporterEnabled, traceControlHeadersEnabled, value
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Map<String, Double> copyRoutes(Map<String, Double> samplingRouteRatios) {
        if (samplingRouteRatios == null) {
            throw new IllegalArgumentException("samplingRouteRatios must not be null");
        }

        return new LinkedHashMap<>(samplingRouteRatios);
    }

    private static double canonicalizeRatio(double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0.0d || ratio > 1.0d) {
            throw new IllegalArgumentException("ratio must be finite and within [0.0, 1.0]");
        }

        return (ratio == 0.0d) ? 0.0d : ratio;
    }

    private static Map<String, Double> canonicalizeRoutes(Map<String, Double> routes) {
        if (routes.size() > JmxWireContract.MAX_ROUTE_ENTRIES) {
            String message = "samplingRouteRatios must contain at most %d entries"
                    .formatted(JmxWireContract.MAX_ROUTE_ENTRIES);

            throw new IllegalArgumentException(message);
        }

        Map<String, Double> canonical = new LinkedHashMap<>(routes.size());
        for (Map.Entry<String, Double> entry : routes.entrySet()) {
            String prefix = requireRoutePrefix(entry.getKey());
            Double ratio = entry.getValue();
            if (ratio == null) {
                throw new IllegalArgumentException("route ratio must not be null");
            }

            canonical.put(prefix, canonicalizeRatio(ratio));
        }

        return canonical;
    }

    private static String requireRoutePrefix(String routePrefix) {
        if (routePrefix == null) {
            throw new IllegalArgumentException("route prefix must not be null");
        }

        if (routePrefix.isBlank()) {
            throw new IllegalArgumentException("route prefix must not be blank");
        }

        if (JmxWireUtf8Meter.utf8Length(routePrefix) > JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES) {
            String message = "route prefix must contain at most %d UTF-8 bytes"
                    .formatted(JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES);

            throw new IllegalArgumentException(message);
        }

        if (routePrefix.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("route prefix must not contain ISO control characters");
        }

        return routePrefix;
    }

    private static String canonicalizeLogLevel(String diagnosticsLogLevel) {
        if (diagnosticsLogLevel == null) {
            throw new IllegalArgumentException("diagnosticsLogLevel must not be null");
        }

        String canonical = diagnosticsLogLevel.toUpperCase(Locale.ROOT);
        if (!JmxWireContract.VALID_LOG_LEVELS.contains(canonical)) {
            throw new IllegalArgumentException("diagnosticsLogLevel is not a recognized level");
        }

        return canonical;
    }

    private static String canonicalizeValidationLevel(String validationLevel) {
        if (validationLevel == null || validationLevel.isBlank()) {
            throw new IllegalArgumentException("validationLevel must not be blank");
        }

        String canonical = validationLevel.toUpperCase(Locale.ROOT);
        if (!JmxWireContract.VALID_VALIDATION_LEVELS.contains(canonical)) {
            throw new IllegalArgumentException("validationLevel is not a recognized level");
        }

        return canonical;
    }

    public static final class Builder {

        private Boolean samplingEnabled;
        private Double samplingRatio;
        private Map<String, Double> samplingRouteRatios;
        private String validationLevel;
        private Boolean exporterEnabled;
        private Boolean traceControlHeadersEnabled;
        private String diagnosticsLogLevel;

        public Builder samplingEnabled(boolean v) {
            this.samplingEnabled = v;
            return this;
        }

        public Builder samplingRatio(double v) {
            this.samplingRatio = v;
            return this;
        }

        public Builder samplingRouteRatios(Map<String, Double> v) {
            this.samplingRouteRatios = v;
            return this;
        }

        public Builder validationLevel(String v) {
            this.validationLevel = v;
            return this;
        }

        public Builder exporterEnabled(boolean v) {
            this.exporterEnabled = v;
            return this;
        }

        public Builder traceControlHeadersEnabled(boolean v) {
            this.traceControlHeadersEnabled = v;
            return this;
        }

        public Builder diagnosticsLogLevel(String v) {
            this.diagnosticsLogLevel = v;
            return this;
        }

        public CompleteTracingSettings build() {
            List<String> missing = getMissingProperties();

            if (!missing.isEmpty()) {
                throw new TracingSettingsManifestException("Incomplete tracing policy manifest, missing required fields: " + missing);
            }

            return new CompleteTracingSettings(
                    samplingEnabled, samplingRatio, samplingRouteRatios,
                    validationLevel, exporterEnabled,
                    traceControlHeadersEnabled, diagnosticsLogLevel
            );
        }

        private List<String> getMissingProperties() {
            List<String> missing = new ArrayList<>();

            if (samplingEnabled == null) {
                missing.add("samplingEnabled");
            }

            if (samplingRatio == null) {
                missing.add("samplingRatio");
            }

            if (samplingRouteRatios == null) {
                missing.add("samplingRouteRatios");
            }

            if (validationLevel == null) {
                missing.add("validationLevel");
            }

            if (exporterEnabled == null) {
                missing.add("exporterEnabled");
            }

            if (traceControlHeadersEnabled == null) {
                missing.add("traceControlHeadersEnabled");
            }

            if (diagnosticsLogLevel == null) {
                missing.add("diagnosticsLogLevel");
            }

            return missing;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof CompleteTracingSettings s)) {
            return false;
        }

        return samplingEnabled == s.samplingEnabled
                && Double.compare(samplingRatio, s.samplingRatio) == 0
                && samplingRouteRatios.equals(s.samplingRouteRatios)
                && validationLevel.equals(s.validationLevel)
                && exporterEnabled == s.exporterEnabled
                && traceControlHeadersEnabled == s.traceControlHeadersEnabled
                && diagnosticsLogLevel.equals(s.diagnosticsLogLevel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationLevel, exporterEnabled,
                traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    @Override
    public String toString() {
        return "CompleteTracingSettings{" +
                "sampling.enabled=" + samplingEnabled +
                ", sampling.ratio=" + samplingRatio +
                ", sampling.route-ratios.size=" + samplingRouteRatios.size() +
                ", validation.level=" + validationLevel +
                ", exporter.enabled=" + exporterEnabled +
                ", propagation.trace-control-headers.enabled=" + traceControlHeadersEnabled +
                ", diagnostics.log-level=" + diagnosticsLogLevel +
                '}';
    }
}
