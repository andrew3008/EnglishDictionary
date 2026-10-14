package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.internal.WinningPropertySourceTraversal;

public final class ConfigServerSettingsManifestBuilder {

    private static final String POLICY = "platform.tracing.policy.";
    private static final String SAMPLING_ENABLED = POLICY + "sampling.enabled";
    private static final String SAMPLING_RATIO = POLICY + "sampling.ratio";
    private static final String VALIDATION_LEVEL = POLICY + "validation.level";
    private static final String EXPORTER_ENABLED = POLICY + "exporter.enabled";
    private static final String TRACE_CONTROL_HEADERS_ENABLED = POLICY + "propagation.trace-control-headers.enabled";
    private static final String DIAGNOSTICS_LOG_LEVEL = POLICY + "diagnostics.log-level";
    private static final String ROUTE_RATIOS = POLICY + "sampling.route-ratios";
    private static final String ROUTE_RATIOS_ENVIRONMENT_VARIABLE = "PLATFORM_TRACING_POLICY_SAMPLING_ROUTE_RATIOS";

    private static final List<String> REQUIRED_KEYS = List.of(
            SAMPLING_ENABLED,
            SAMPLING_RATIO,
            VALIDATION_LEVEL,
            EXPORTER_ENABLED,
            TRACE_CONTROL_HEADERS_ENABLED,
            DIAGNOSTICS_LOG_LEVEL);

    private final ConfigurableEnvironment environment;
    private final ConfigServerSettingsProvenanceClassifier provenance;

    public ConfigServerSettingsManifestBuilder(ConfigurableEnvironment environment,
                                               ConfigServerSettingsProvenanceClassifier provenance) {
        this.environment = requireNonNull(environment, "environment");
        this.provenance = requireNonNull(provenance, "provenance");
    }

    public CompleteTracingSettings build() {
        REQUIRED_KEYS.forEach(this::requirePresentFromConfigServer);

        return CompleteTracingSettings.builder()
                .samplingEnabled(value(SAMPLING_ENABLED, Boolean.class))
                .samplingRatio(value(SAMPLING_RATIO, Double.class))
                .samplingRouteRatios(routeRatios())
                .validationLevel(value(VALIDATION_LEVEL, String.class))
                .exporterEnabled(value(EXPORTER_ENABLED, Boolean.class))
                .traceControlHeadersEnabled(value(TRACE_CONTROL_HEADERS_ENABLED, Boolean.class))
                .diagnosticsLogLevel(value(DIAGNOSTICS_LOG_LEVEL, String.class))
                .build();
    }

    private void requirePresentFromConfigServer(String key) {
        if (!environment.containsProperty(key)) {
            throw new TracingSettingsManifestException("Required policy key missing: '%s'".formatted(key));
        }

        requireConfigServerProvenance(key);
    }

    private void requireConfigServerProvenance(String key) {
        switch (provenance.classify(key)) {
            case AUTHORITATIVE -> {
            }

            case LOCAL_OVERRIDE -> throw new TracingSettingsManifestException(
                    "Local override rejected for policy key '%s' (source='%s')"
                            .formatted(key, provenance.originSourceName(key)));

            case UNKNOWN -> throw new TracingSettingsManifestException(
                    "Unknown property source for policy key '%s' (source='%s'); only Config Server sources are accepted"
                            .formatted(key, provenance.originSourceName(key)));
        }
    }

    private <T> T value(String key, Class<T> type) {
        try {
            return environment.getRequiredProperty(key, type);
        } catch (ConversionException | IllegalStateException invalid) {
            throw new TracingSettingsManifestException(
                    "Policy key '%s' has an empty value or a value not convertible to %s"
                            .formatted(key, type.getSimpleName()),
                    invalid);
        }
    }

    private Map<String, Double> routeRatios() {
        Map<String, Double> routeRatios = new LinkedHashMap<>();

        for (String propertyName : routeRatioPropertyNames()) {
            requireConfigServerProvenance(propertyName);
            String route = routeOf(propertyName);
            double ratio = value(propertyName, Double.class);

            Double previous = routeRatios.putIfAbsent(route, ratio);
            if (previous != null && Double.compare(previous, ratio) != 0) {
                throw new TracingSettingsManifestException(
                        "Conflicting values for route '%s' spelled in both dotted and bracket form".formatted(route));
            }
        }

        return Collections.unmodifiableMap(routeRatios);
    }

    private Set<String> routeRatioPropertyNames() {
        Set<String> names = new LinkedHashSet<>();
        WinningPropertySourceTraversal.visitPropertyNames(environment, (source, name) -> {
            if (source instanceof SystemEnvironmentPropertySource
                    && name.startsWith(ROUTE_RATIOS_ENVIRONMENT_VARIABLE)) {
                throw new TracingSettingsManifestException(
                        "Local override rejected for route ratios set via environment variable '%s'".formatted(name));
            }

            if (name.startsWith(ROUTE_RATIOS + '.') || name.startsWith(ROUTE_RATIOS + '[')) {
                names.add(name);
            }
        });

        return names;
    }

    private static String routeOf(String propertyName) {
        String suffix = propertyName.substring(ROUTE_RATIOS.length());
        if (suffix.charAt(0) == '.') {
            return suffix.substring(1);
        }

        if (!suffix.endsWith("]")) {
            throw new TracingSettingsManifestException(
                    "Malformed bracketed route ratio key '%s'".formatted(propertyName));
        }

        return suffix.substring(1, suffix.length() - 1);
    }
}
