package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable complete Category A policy snapshot.
 * Contains exactly eight leaves. Never partial.
 * <p>
 * This is the application-side representation of the policy to be delivered
 * over the private JMX boundary. It is NOT shared with the Agent as a DTO;
 * it is encoded via {@link space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec}.
 */
public final class CompleteTracingPolicySnapshot {

    private final boolean samplingEnabled;
    private final double samplingRatio;
    private final Map<String, Double> samplingRouteRatios;
    private final boolean validationEnabled;
    private final boolean validationStrict;
    private final boolean exporterEnabled;
    private final boolean traceControlHeadersEnabled;
    private final String diagnosticsLogLevel;

    private CompleteTracingPolicySnapshot(
            boolean samplingEnabled,
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

    public boolean samplingEnabled() { return samplingEnabled; }
    public double samplingRatio() { return samplingRatio; }
    public Map<String, Double> samplingRouteRatios() { return samplingRouteRatios; }
    public boolean validationEnabled() { return validationEnabled; }
    public boolean validationStrict() { return validationStrict; }
    public boolean exporterEnabled() { return exporterEnabled; }
    public boolean traceControlHeadersEnabled() { return traceControlHeadersEnabled; }
    public String diagnosticsLogLevel() { return diagnosticsLogLevel; }

    public CompleteTracingPolicySnapshot withSamplingEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(value, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel);
    }

    public CompleteTracingPolicySnapshot withSamplingRatio(double value) {
        return new CompleteTracingPolicySnapshot(samplingEnabled, value, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel);
    }

    public CompleteTracingPolicySnapshot withSamplingRouteRatios(Map<String, Double> value) {
        return new CompleteTracingPolicySnapshot(samplingEnabled, samplingRatio, value,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel);
    }

    public CompleteTracingPolicySnapshot withValidationEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(samplingEnabled, samplingRatio, samplingRouteRatios,
                value, validationStrict, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel);
    }

    public CompleteTracingPolicySnapshot withValidationStrict(boolean value) {
        return new CompleteTracingPolicySnapshot(samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, value, exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel);
    }

    public CompleteTracingPolicySnapshot withExporterEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, value, traceControlHeadersEnabled, diagnosticsLogLevel);
    }

    public CompleteTracingPolicySnapshot withTraceControlHeadersEnabled(boolean value) {
        return new CompleteTracingPolicySnapshot(samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, value, diagnosticsLogLevel);
    }

    public CompleteTracingPolicySnapshot withDiagnosticsLogLevel(String value) {
        return new CompleteTracingPolicySnapshot(samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, value);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean samplingEnabled = true;
        private double samplingRatio = 1.0;
        private Map<String, Double> samplingRouteRatios = Collections.emptyMap();
        private boolean validationEnabled = true;
        private boolean validationStrict = false;
        private boolean exporterEnabled = true;
        private boolean traceControlHeadersEnabled = true;
        private String diagnosticsLogLevel = "INFO";

        public Builder samplingEnabled(boolean v) { this.samplingEnabled = v; return this; }
        public Builder samplingRatio(double v) { this.samplingRatio = v; return this; }
        public Builder samplingRouteRatios(Map<String, Double> v) { this.samplingRouteRatios = v; return this; }
        public Builder validationEnabled(boolean v) { this.validationEnabled = v; return this; }
        public Builder validationStrict(boolean v) { this.validationStrict = v; return this; }
        public Builder exporterEnabled(boolean v) { this.exporterEnabled = v; return this; }
        public Builder traceControlHeadersEnabled(boolean v) { this.traceControlHeadersEnabled = v; return this; }
        public Builder diagnosticsLogLevel(String v) { this.diagnosticsLogLevel = v; return this; }

        public CompleteTracingPolicySnapshot build() {
            return new CompleteTracingPolicySnapshot(
                    samplingEnabled, samplingRatio, samplingRouteRatios,
                    validationEnabled, validationStrict, exporterEnabled,
                    traceControlHeadersEnabled, diagnosticsLogLevel);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompleteTracingPolicySnapshot s)) return false;
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
        return Objects.hash(samplingEnabled, samplingRatio, samplingRouteRatios,
                validationEnabled, validationStrict, exporterEnabled,
                traceControlHeadersEnabled, diagnosticsLogLevel);
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
