package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
            "platform.tracing.policy.validation.level",
            "platform.tracing.policy.exporter.enabled",
            "platform.tracing.policy.propagation.trace-control-headers.enabled",
            "platform.tracing.policy.diagnostics.log-level"
    );

    private static final String ROUTE_RATIOS_PREFIX = "platform.tracing.policy.sampling.route-ratios";

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
                        "Unknown property source for key '%s' (source='%s'). Only recognised Config Server sources are accepted."
                                .formatted(key, provenance.originSourceName(key))
                );
            }
        }

        boolean samplingEnabled = environment.getRequiredProperty("platform.tracing.policy.sampling.enabled", Boolean.class);
        double samplingRatio = environment.getRequiredProperty("platform.tracing.policy.sampling.ratio", Double.class);

        Map<String, Double> routeRatios = parseRouteRatios();
        String validationLevel = environment.getRequiredProperty("platform.tracing.policy.validation.level");
        boolean exporterEnabled = environment.getRequiredProperty("platform.tracing.policy.exporter.enabled", Boolean.class);
        boolean traceControlHeaders = environment.getRequiredProperty("platform.tracing.policy.propagation.trace-control-headers.enabled", Boolean.class);
        String logLevel = environment.getRequiredProperty("platform.tracing.policy.diagnostics.log-level");

        return CompleteTracingSettings.builder()
                .samplingEnabled(samplingEnabled)
                .samplingRatio(samplingRatio)
                .samplingRouteRatios(routeRatios)
                .validationLevel(validationLevel)
                .exporterEnabled(exporterEnabled)
                .traceControlHeadersEnabled(traceControlHeaders)
                .diagnosticsLogLevel(logLevel)
                .build();
    }

    private Map<String, Double> parseRouteRatios() {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return Map.of();
        }

        Map<String, Double> result = new LinkedHashMap<>();
        try {
            for (String propertyName : collectRouteRatioPropertyNames(configurable)) {
                rejectIfProvenanceUnsafe(propertyName);
                putRouteRatio(result, propertyName);
            }
        } catch (TracingSettingsManifestException e) {
            throw e;
        } catch (Exception e) {
            throw new TracingSettingsManifestException("Unexpected error parsing route-ratios from Config Server", e);
        }

        return result;
    }

    private static Set<String> collectRouteRatioPropertyNames(ConfigurableEnvironment configurable) {
        Set<String> names = new LinkedHashSet<>();
        for (PropertySource<?> propertySource : configurable.getPropertySources()) {
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }

            for (String propertyName : enumerable.getPropertyNames()) {
                if (isRouteRatioProperty(propertyName)) {
                    names.add(propertyName);
                }
            }
        }
        return names;
    }

    private static boolean isRouteRatioProperty(String propertyName) {
        return propertyName.startsWith(ROUTE_RATIOS_PREFIX + '.')
                || propertyName.startsWith(ROUTE_RATIOS_PREFIX + '[');
    }

    private void putRouteRatio(Map<String, Double> target, String propertyName) {
        String route = routeOf(propertyName);
        double ratio = parseRouteRatioValue(propertyName);

        Double alreadyBound = target.putIfAbsent(route, ratio);
        if (alreadyBound != null && Double.compare(alreadyBound, ratio) != 0) {
            throw new TracingSettingsManifestException(
                    "Conflicting values for one sampling.route-ratios route spelled in both dotted and bracket form");
        }
    }

    private static String routeOf(String propertyName) {
        String suffix = propertyName.substring(ROUTE_RATIOS_PREFIX.length());
        if (suffix.charAt(0) != '[') {
            return suffix.substring(1);
        }

        if (!suffix.endsWith("]")) {
            throw new TracingSettingsManifestException("Malformed bracketed sampling.route-ratios key");
        }
        return suffix.substring(1, suffix.length() - 1);
    }

    private void rejectIfProvenanceUnsafe(String propertyName) {
        ConfigServerSettingsProvenanceClassifier.ProvenanceClass provenanceClass = provenance.classify(propertyName);
        if (provenanceClass == ConfigServerSettingsProvenanceClassifier.ProvenanceClass.LOCAL_OVERRIDE) {
            throw new TracingSettingsManifestException("Local override rejected for a sampling.route-ratios entry");
        }

        if (provenanceClass == ConfigServerSettingsProvenanceClassifier.ProvenanceClass.UNKNOWN) {
            throw new TracingSettingsManifestException(
                    "Unknown property source for a sampling.route-ratios entry (source='%s'). Only recognised Config Server sources are accepted."
                            .formatted(provenance.originSourceName(propertyName))
            );
        }
    }

    private double parseRouteRatioValue(String propertyName) {
        String raw = environment.getProperty(propertyName);
        if (raw == null) {
            throw new TracingSettingsManifestException("Null value for a sampling.route-ratios entry");
        }

        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new TracingSettingsManifestException("Invalid double value for a sampling.route-ratios entry");
        }
    }
}
