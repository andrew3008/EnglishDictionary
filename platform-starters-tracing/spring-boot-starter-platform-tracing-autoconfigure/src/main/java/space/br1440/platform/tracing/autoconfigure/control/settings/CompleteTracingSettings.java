package space.br1440.platform.tracing.autoconfigure.control.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CompleteTracingSettings {

    private static final int MAX_ROUTE_RATIOS = 100;
    private static final int MAX_ROUTE_KEY_LENGTH = 255;
    private static final Set<String> VALID_LOG_LEVELS = Set.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    private final boolean samplingEnabled;
    private final double samplingRatio;
    private final Map<String, Double> samplingRouteRatios;
    private final boolean validationEnabled;
    private final boolean validationStrict;
    private final boolean exporterEnabled;
    private final boolean traceControlHeadersEnabled;
    private final String diagnosticsLogLevel;

    private CompleteTracingSettings(boolean samplingEnabled,
                                          double samplingRatio,
                                          Map<String, Double> samplingRouteRatios,
                                          boolean validationEnabled,
                                          boolean validationStrict,
                                          boolean exporterEnabled,
                                          boolean traceControlHeadersEnabled,
                                          String diagnosticsLogLevel) {
        Map<String, Double> copiedRoutes = copyRoutes(samplingRouteRatios);

        this.samplingEnabled = samplingEnabled;
        this.samplingRatio = canonicalizeRatio(samplingRatio);
        this.samplingRouteRatios = Collections.unmodifiableMap(canonicalizeRoutes(copiedRoutes));
        this.validationEnabled = validationEnabled;
        this.validationStrict = validationStrict;
        this.exporterEnabled = exporterEnabled;
        this.traceControlHeadersEnabled = traceControlHeadersEnabled;
        this.diagnosticsLogLevel = canonicalizeLogLevel(diagnosticsLogLevel);
    }

    public static CompleteTracingSettings helmBootstrapDefaults() {
        return new CompleteTracingSettings(
                true, 1.0, Map.of(),
                true, false, true, true, "INFO"
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

    public boolean validationEnabled() {
        return validationEnabled;
    }

    public boolean validationStrict() {
        return validationStrict;
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
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withSamplingRatio(double value) {
        return new CompleteTracingSettings(
                samplingEnabled, value, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withSamplingRouteRatios(Map<String, Double> value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, value,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withValidationEnabled(boolean value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                value, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withValidationStrict(boolean value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, value, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withExporterEnabled(boolean value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, value, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withTraceControlHeadersEnabled(boolean value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, value, diagnosticsLogLevel
        );
    }

    public CompleteTracingSettings withDiagnosticsLogLevel(String value) {
        return new CompleteTracingSettings(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, value
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
        if (routes.size() > MAX_ROUTE_RATIOS) {
            throw new IllegalArgumentException("samplingRouteRatios must contain at most " + MAX_ROUTE_RATIOS + " entries");
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

        if (routePrefix.length() > MAX_ROUTE_KEY_LENGTH) {
            throw new IllegalArgumentException("route prefix must contain at most " + MAX_ROUTE_KEY_LENGTH + " UTF-16 code units");
        }

        if (routePrefix.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("route prefix must not contain ISO control characters");
        }

        return routePrefix;
    }

    public static boolean isRecognizedLogLevel(String diagnosticsLogLevel) {
        return VALID_LOG_LEVELS.contains(diagnosticsLogLevel);
    }

    private static String canonicalizeLogLevel(String diagnosticsLogLevel) {
        if (diagnosticsLogLevel == null) {
            throw new IllegalArgumentException("diagnosticsLogLevel must not be null");
        }

        String canonical = diagnosticsLogLevel.toUpperCase(Locale.ROOT);
        if (!isRecognizedLogLevel(canonical)) {
            throw new IllegalArgumentException("diagnosticsLogLevel is not a recognized level");
        }

        return canonical;
    }

    public static final class Builder {

        private Boolean samplingEnabled;
        private Double samplingRatio;
        private Map<String, Double> samplingRouteRatios;
        private Boolean validationEnabled;
        private Boolean validationStrict;
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

        public Builder validationEnabled(boolean v) {
            this.validationEnabled = v;
            return this;
        }

        public Builder validationStrict(boolean v) {
            this.validationStrict = v;
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
                    validationEnabled, validationStrict, exporterEnabled,
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

            if (validationEnabled == null) {
                missing.add("validationEnabled");
            }

            if (validationStrict == null) {
                missing.add("validationStrict");
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
                && validationEnabled == s.validationEnabled
                && validationStrict == s.validationStrict
                && exporterEnabled == s.exporterEnabled
                && traceControlHeadersEnabled == s.traceControlHeadersEnabled
                && diagnosticsLogLevel.equals(s.diagnosticsLogLevel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled,
                traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    @Override
    public String toString() {
        return "CompleteTracingSettings{" +
                "sampling.enabled=" + samplingEnabled +
                ", sampling.ratio=" + samplingRatio +
                ", sampling.route-ratios.size=" + samplingRouteRatios.size() +
                ", validation.enabled=" + validationEnabled +
                ", validation.strict=" + validationStrict +
                ", exporter.enabled=" + exporterEnabled +
                ", propagation.trace-control-headers.enabled=" + traceControlHeadersEnabled +
                ", diagnostics.log-level=" + diagnosticsLogLevel +
                '}';
    }
}
