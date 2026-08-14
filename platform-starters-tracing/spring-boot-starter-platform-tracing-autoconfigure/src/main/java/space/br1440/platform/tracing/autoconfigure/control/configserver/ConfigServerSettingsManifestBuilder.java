package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class ConfigServerSettingsManifestBuilder {

    private static final Set<String> REQUIRED_KEYS = Set.of(
            "platform.tracing.policy.sampling.enabled",
            "platform.tracing.policy.sampling.ratio",
            "platform.tracing.policy.validation.enabled",
            "platform.tracing.policy.validation.strict",
            "platform.tracing.policy.exporter.enabled",
            "platform.tracing.policy.propagation.trace-control-headers.enabled",
            "platform.tracing.policy.diagnostics.log-level"
    );

    private final Environment environment;
    private final ConfigServerSettingsProvenanceClassifier provenance;

    public CompleteTracingSettings build() {
        for (String key : REQUIRED_KEYS) {
            if (!environment.containsProperty(key)) {
                throw new TracingSettingsManifestException("Required Category A policy key missing: '" + key + "'");
            }

            ConfigServerSettingsProvenanceClassifier.ProvenanceClass pClass = provenance.classify(key);
            if (pClass == ConfigServerSettingsProvenanceClassifier.ProvenanceClass.LOCAL_OVERRIDE) {
                throw new TracingSettingsManifestException("Local override rejected for policy key '" + key + "'");
            }

            if (pClass == ConfigServerSettingsProvenanceClassifier.ProvenanceClass.UNKNOWN) {
                throw new TracingSettingsManifestException(
                        "Unknown property source for key '" + key +
                                "'. Only recognised Config Server sources are accepted."
                );
            }
        }

        boolean samplingEnabled = environment.getRequiredProperty("platform.tracing.policy.sampling.enabled", Boolean.class);
        double samplingRatio = environment.getRequiredProperty("platform.tracing.policy.sampling.ratio", Double.class);

        Map<String, Double> routeRatios = parseRouteRatios();
        boolean validationEnabled = environment.getRequiredProperty("platform.tracing.policy.validation.enabled", Boolean.class);
        boolean validationStrict = environment.getRequiredProperty("platform.tracing.policy.validation.strict", Boolean.class);
        boolean exporterEnabled = environment.getRequiredProperty("platform.tracing.policy.exporter.enabled", Boolean.class);
        boolean traceControlHeaders = environment.getRequiredProperty("platform.tracing.policy.propagation.trace-control-headers.enabled", Boolean.class);
        String logLevel = environment.getRequiredProperty("platform.tracing.policy.diagnostics.log-level");

        return CompleteTracingSettings.builder()
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
            if (!(environment instanceof ConfigurableEnvironment ce)) {
                return result;
            }

            for (PropertySource<?> ps : ce.getPropertySources()) {
                if (!(ps instanceof EnumerablePropertySource<?> eps)) {
                    continue;
                }

                for (String propName : eps.getPropertyNames()) {
                    if (!propName.startsWith(prefix + ".")) {
                        continue;
                    }

                    String route = propName.substring(prefix.length() + 1);
                    String val = environment.getProperty(propName);
                    if (val == null) {
                        throw new TracingSettingsManifestException("Null value for a sampling.route-ratios entry");
                    }

                    double ratio;
                    try {
                        ratio = Double.parseDouble(val);
                    } catch (NumberFormatException e) {
                        throw new TracingSettingsManifestException("Invalid double value for a sampling.route-ratios entry");
                    }

                    if (result.containsKey(route)) {
                        throw new TracingSettingsManifestException("Duplicate route-ratio key in Config Server manifest");
                    }

                    result.put(route, ratio);
                }
            }
        } catch (TracingSettingsManifestException e) {
            throw e;
        } catch (Exception e) {
            throw new TracingSettingsManifestException("Unexpected error parsing route-ratios from Config Server");
        }

        return result;
    }
}
