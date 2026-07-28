package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyManifestException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds a complete eight-leaf Category A manifest from the Spring Environment.
 * <p>
 * Fail-closed: any missing required leaf causes {@link TracingPolicyManifestException}.
 * Local overrides (command-line, system property, local file) for policy.* keys are rejected.
 * Returns a complete {@link CompleteTracingPolicySnapshot}.
 */
public final class ConfigServerPolicyManifestBuilder {

    private static final Set<String> REQUIRED_KEYS = Set.of(
            "platform.tracing.policy.sampling.enabled",
            "platform.tracing.policy.sampling.ratio",
            "platform.tracing.policy.sampling.route-ratios",
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
                        "Required Category A policy key missing: '" + key +
                        "'. All eight leaves must be present in Config Server manifest.");
            }
            ConfigServerPolicyProvenanceClassifier.ProvenanceClass pClass = provenance.classify(key);
            if (pClass == ConfigServerPolicyProvenanceClassifier.ProvenanceClass.LOCAL_OVERRIDE) {
                throw new TracingPolicyManifestException(
                        "Local override rejected for policy key '" + key +
                        "'. Category A leaves must originate from Config Server, not local configuration.");
            }
        }

        boolean samplingEnabled = environment.getRequiredProperty(
                "platform.tracing.policy.sampling.enabled", Boolean.class);
        double samplingRatio = environment.getRequiredProperty(
                "platform.tracing.policy.sampling.ratio", Double.class);
        Map<String, Double> routeRatios = parseRouteRatios();
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

    @SuppressWarnings("unchecked")
    private Map<String, Double> parseRouteRatios() {
        // Spring binds Map<String,Double> from platform.tracing.policy.sampling.route-ratios.*
        // Attempt to read the bound map; fall back to empty if binding is not map-style
        try {
            // Convention: prefix binding produces Map via BindResult
            Map<String, String> raw = new LinkedHashMap<>();
            String prefix = "platform.tracing.policy.sampling.route-ratios";
            // iterate over any environment properties with this prefix
            if (environment instanceof org.springframework.core.env.ConfigurableEnvironment ce) {
                for (org.springframework.core.env.PropertySource<?> ps : ce.getPropertySources()) {
                    if (ps instanceof org.springframework.core.env.EnumerablePropertySource<?> eps) {
                        for (String name : eps.getPropertyNames()) {
                            if (name.startsWith(prefix + ".")) {
                                String route = name.substring(prefix.length() + 1);
                                String val = environment.getProperty(name);
                                if (val != null) raw.put(route, val);
                            }
                        }
                    }
                }
            }
            Map<String, Double> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                result.put(entry.getKey(), Double.parseDouble(entry.getValue()));
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
