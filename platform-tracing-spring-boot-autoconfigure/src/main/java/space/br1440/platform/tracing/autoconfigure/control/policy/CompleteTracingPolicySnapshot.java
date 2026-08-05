package space.br1440.platform.tracing.autoconfigure.control.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CompleteTracingPolicySnapshot {

    private final boolean samplingEnabled;
    private final double samplingRatio;
    private final Map<String, Double> samplingRouteRatios;
    private final boolean validationEnabled;
    private final boolean validationStrict;
    private final boolean exporterEnabled;
    private final boolean traceControlHeadersEnabled;
    private final String diagnosticsLogLevel;

    private CompleteTracingPolicySnapshot(boolean samplingEnabled,
                                          double samplingRatio,
                                          Map<String, Double> samplingRouteRatios,
                                          boolean validationEnabled,
                                          boolean validationStrict,
                                          boolean exporterEnabled,
                                          boolean traceControlHeadersEnabled,
                                          String diagnosticsLogLevel) {
        this.samplingEnabled = samplingEnabled;
        this.samplingRatio = samplingRatio;
        this.samplingRouteRatios = Collections.unmodifiableMap(new LinkedHashMap<>(samplingRouteRatios));
        this.validationEnabled = validationEnabled;
        this.validationStrict = validationStrict;
        this.exporterEnabled = exporterEnabled;
        this.traceControlHeadersEnabled = traceControlHeadersEnabled;
        this.diagnosticsLogLevel = Objects.requireNonNull(diagnosticsLogLevel, "diagnosticsLogLevel");
    }

    public static CompleteTracingPolicySnapshot helmBootstrapDefaults() {
        return new CompleteTracingPolicySnapshot(
                true, 1.0, Map.of(),
                true, false, true, true, "INFO");
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

    public CompleteTracingPolicySnapshot withSamplingEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(
                value, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingPolicySnapshot withSamplingRatio(double value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled, value, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingPolicySnapshot withSamplingRouteRatios(Map<String, Double> value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled, samplingRatio, value,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingPolicySnapshot withValidationEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                value, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingPolicySnapshot withValidationStrict(boolean value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, value, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingPolicySnapshot withExporterEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, value, traceControlHeadersEnabled, diagnosticsLogLevel
        );
    }

    public CompleteTracingPolicySnapshot withTraceControlHeadersEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, value, diagnosticsLogLevel
        );
    }

    public CompleteTracingPolicySnapshot withDiagnosticsLogLevel(String value) {
        return new CompleteTracingPolicySnapshot(
                samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, value
        );
    }

    public static Builder builder() {
        return new Builder();
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

        public CompleteTracingPolicySnapshot build() {
            List<String> missing = getMissingProperties();

            if (!missing.isEmpty()) {
                throw new TracingPolicyManifestException("Incomplete tracing policy manifest, missing required fields: " + missing);
            }

            if (samplingRatio < 0.0 || samplingRatio > 1.0) {
                throw new TracingPolicyManifestException("samplingRatio must be within [0.0, 1.0], got: " + samplingRatio);
            }

            samplingRouteRatios.forEach((route, ratio) -> {
                if (ratio == null || ratio < 0.0 || ratio > 1.0) {
                    throw new TracingPolicyManifestException("samplingRouteRatios[" + route + "] must be within [0.0, 1.0], got: " + ratio);
                }
            });

            return new CompleteTracingPolicySnapshot(
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

        if (!(o instanceof CompleteTracingPolicySnapshot s)) {
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
        return "CompleteTracingPolicySnapshot{" +
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
