package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyManifestException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds a complete eight-leaf Category A manifest from the Spring Environment.
 * Fail-closed: missing leaf, local override, unknown provenance, or route-ratio parse error
 * all throw {@link TracingPolicyManifestException}.
 */
public final class ConfigServerPolicyManifestBuilder {

    private static final Set<String> REQUIRED_KEYS = Set.of(
            "platform.tracing.policy.sampling.enabled",
            "platform.tracing.policy.sampling.ratio",
            "platform.tracing.policy.validation.enabled",
            "platform.tracing.policy.validation.strict",
            "platform.tracing.policy.exporter.enabled",
            "platform.tracing.policy.propagation.trace-control-headers.enabled",
            "platform.tracing.policy.diagnostics.log-level"
    );

    private final org.springframework.core.env.Environment environment;
    private final ConfigServerPolicyProvenanceClassifier provenance;

    public ConfigServerPolicyManifestBuilder(
            org.springframework.core.env.Environment environment,
            ConfigServerPolicyProvenanceClassifier provenance) {
        this.environment = environment;
        this.provenance = provenance;
    }

    public CompleteTracingPolicySnapshot build() {
        for (String key : REQUIRED_KEYS) {
            if (!environment.containsProperty(key)) {
                throw new TracingPolicyManifestException(
                        "Required Category A policy key missing: '" + key + "'");
            }
            ConfigServerPolicyProvenanceClassifier.ProvenanceClass pClass = provenance.classify(key);
            if (pClass == ConfigServerPolicyProvenanceClassifier.ProvenanceClass.LOCAL_OVERRIDE) {
                throw new TracingPolicyManifestException(
                        "Local override rejected for policy key '" + key + "'");
            }
            if (pClass == ConfigServerPolicyProvenanceClassifier.ProvenanceClass.UNKNOWN) {
                throw new TracingPolicyManifestException(
                        "Unknown property source for key '" + key +
                        "'. Only recognised Config Server sources are accepted.");
            }
        }

        boolean samplingEnabled = environment.getRequiredProperty(
                "platform.tracing.policy.sampling.enabled", Boolean.class);
        double samplingRatio = environment.getRequiredProperty(
                "platform.tracing.policy.sampling.ratio", Double.class);
        Map<String, Double> routeRatios = parseRouteRatios(); // throws TracingPolicyManifestException on any error
        boolean validationEnabled = environment.getRequiredProperty(
                "platform.tracing.policy.validation.enabled", Boolean.class);
        boolean validationStrict = environment.getRequiredProperty(
                "platform.tracing.policy.validation.strict", Boolean.class);
        boolean exporterEnabled = environment.getRequiredProperty(
                "platform.tracing.policy.exporter.enabled", Boolean.class);
        boolean traceControlHeaders = environment.getRequiredProperty(
                "platform.tracing.policy.propagation.trace-control-headers.enabled", Boolean.class);
        String logLevel = environment.getRequiredProperty(
                "platform.tracing.policy.diagnostics.log-level");

        return CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(samplingEnabled)
                .samplingRatio(samplingRatio)
                .samplingRouteRatios(routeRatios)
                .validationEnabled(validationEnabled)
                .validationStrict(validationStrict)
                .exporterEnabled(exporterEnabled)
                .traceControlHeadersEnabled(traceControlHeaders)
                .diagnosticsLogLevel(logLevel)
                .build();
    }

    private Map<String, Double> parseRouteRatios() {
        String prefix = "platform.tracing.policy.sampling.route-ratios";
        Map<String, Double> result = new LinkedHashMap<>();
        try {
            if (!(environment instanceof org.springframework.core.env.ConfigurableEnvironment ce)) {
                // Non-configurable env: assume empty route ratios is valid
                return result;
            }
            for (org.springframework.core.env.PropertySource<?> ps : ce.getPropertySources()) {
                if (!(ps instanceof org.springframework.core.env.EnumerablePropertySource<?> eps)) continue;
                for (String propName : eps.getPropertyNames()) {
                    if (!propName.startsWith(prefix + ".")) continue;
                    String route = propName.substring(prefix.length() + 1);
                    String val = environment.getProperty(propName);
                    if (val == null) {
                        throw new TracingPolicyManifestException(
                                "Null value for route-ratio key '" + propName + "'");
                    }
                    double ratio;
                    try {
                        ratio = Double.parseDouble(val);
                    } catch (NumberFormatException e) {
                        throw new TracingPolicyManifestException(
                                "Invalid double value '" + val + "' for route-ratio key '" + propName + "'");
                    }
                    if (ratio < 0.0 || ratio > 1.0) {
                        throw new TracingPolicyManifestException(
                                "Route ratio out of range [0,1]: '" + val + "' for key '" + propName + "'");
                    }
                    if (result.containsKey(route)) {
                        throw new TracingPolicyManifestException(
                                "Duplicate route-ratio key '" + route + "' in Config Server manifest");
                    }
                    result.put(route, ratio);
                }
            }
        } catch (TracingPolicyManifestException e) {
            throw e; // re-throw as-is
        } catch (Exception e) {
            throw new TracingPolicyManifestException(
                    "Unexpected error parsing route-ratios from Config Server: " + e.getMessage());
        }
        return result;
    }
}
