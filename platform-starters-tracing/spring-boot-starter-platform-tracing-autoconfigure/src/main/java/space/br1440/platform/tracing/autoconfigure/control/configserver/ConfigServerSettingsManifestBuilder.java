package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingPropertyAdmission;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;

public final class ConfigServerSettingsManifestBuilder {

    private static final String POLICY = "platform.tracing.policy.";
    private static final String SAMPLING_ENABLED = POLICY + "sampling.enabled";
    private static final String SAMPLING_RATIO = POLICY + "sampling.ratio";
    private static final String VALIDATION_LEVEL = POLICY + "validation.level";
    private static final String EXPORTER_ENABLED = POLICY + "exporter.enabled";
    private static final String TRACE_CONTROL_HEADERS_ENABLED = POLICY + "propagation.trace-control-headers.enabled";
    private static final String DIAGNOSTICS_LOG_LEVEL = POLICY + "diagnostics.log-level";
    private static final String ROUTE_RATIOS = POLICY + "sampling.route-ratios";
    private static final String CONFIGURATION_PROPERTIES_FACADE = "configurationProperties";
    private static final List<String> REQUIRED_KEYS = List.of(
            SAMPLING_ENABLED,
            SAMPLING_RATIO,
            VALIDATION_LEVEL,
            EXPORTER_ENABLED,
            TRACE_CONTROL_HEADERS_ENABLED,
            DIAGNOSTICS_LOG_LEVEL
    );

    private final ConfigurableEnvironment environment;

    public ConfigServerSettingsManifestBuilder(ConfigurableEnvironment environment) {
        this.environment = requireNonNull(environment, "environment");
    }

    public CompleteTracingSettings build() {
        rejectAdmissionViolations();

        Set<String> policyNames = policyPropertyNames();
        rejectUnknownPolicyKeys(policyNames);
        REQUIRED_KEYS.forEach(this::requirePresent);

        return CompleteTracingSettings.builder()
                .samplingEnabled(value(SAMPLING_ENABLED, Boolean.class))
                .samplingRatio(value(SAMPLING_RATIO, Double.class))
                .samplingRouteRatios(routeRatios(policyNames))
                .validationLevel(value(VALIDATION_LEVEL, String.class))
                .exporterEnabled(value(EXPORTER_ENABLED, Boolean.class))
                .traceControlHeadersEnabled(value(TRACE_CONTROL_HEADERS_ENABLED, Boolean.class))
                .diagnosticsLogLevel(value(DIAGNOSTICS_LOG_LEVEL, String.class))
                .build();
    }

    private void rejectAdmissionViolations() {
        List<TracingPropertyAdmission.Violation> violations = TracingPropertyAdmission.check(
                environment,
                TracingControlChannel.CONFIG_SERVER
        );

        if (!violations.isEmpty()) {
            throw new TracingSettingsManifestException(
                    TracingPropertyAdmission.describe(TracingControlChannel.CONFIG_SERVER, violations));
        }
    }

    private static void rejectUnknownPolicyKeys(Set<String> policyNames) {
        List<String> unknown = policyNames.stream()
                .filter(name -> !REQUIRED_KEYS.contains(name) && !isRouteRatio(name))
                .toList();

        if (!unknown.isEmpty()) {
            throw new TracingSettingsManifestException("Unknown policy key(s): " + unknown);
        }
    }

    private void requirePresent(String key) {
        if (!environment.containsProperty(key)) {
            throw new TracingSettingsManifestException("Required policy key missing: '%s'".formatted(key));
        }
    }

    private <T> T value(String key, Class<T> type) {
        try {
            return environment.getRequiredProperty(key, type);
        } catch (ConversionException | IllegalStateException invalid) {
            String message = "Policy key '%s' has an empty value or a value not convertible to %s"
                    .formatted(key, type.getSimpleName());

            throw new TracingSettingsManifestException(message, invalid);
        }
    }

    private Map<String, Double> routeRatios(Set<String> policyNames) {
        Map<String, Double> routeRatios = new LinkedHashMap<>();

        for (String propertyName : policyNames) {
            if (isRouteRatio(propertyName)) {
                String route = routeOf(propertyName);
                double ratio = value(propertyName, Double.class);

                Double previous = routeRatios.putIfAbsent(route, ratio);
                if (previous != null && Double.compare(previous, ratio) != 0) {
                    throw new TracingSettingsManifestException(
                            "Conflicting values for route '%s' spelled in both dotted and bracket form".formatted(route)
                    );
                }
            }
        }

        return Collections.unmodifiableMap(routeRatios);
    }

    private Set<String> policyPropertyNames() {
        Set<String> names = new LinkedHashSet<>();
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            collectPolicyNames(propertySource, names);
        }
        return names;
    }

    private static void collectPolicyNames(PropertySource<?> propertySource, Set<String> names) {
        if (CONFIGURATION_PROPERTIES_FACADE.equals(propertySource.getName())) {
            return;
        }

        if (propertySource instanceof CompositePropertySource composite) {
            for (PropertySource<?> nested : composite.getPropertySources()) {
                collectPolicyNames(nested, names);
            }

            return;
        }

        if (propertySource instanceof EnumerablePropertySource<?> enumerable) {
            for (String name : enumerable.getPropertyNames()) {
                if (name.startsWith(POLICY)) {
                    names.add(name);
                }
            }
        }
    }

    private static boolean isRouteRatio(String propertyName) {
        return propertyName.startsWith(ROUTE_RATIOS + ".") || propertyName.startsWith(ROUTE_RATIOS + "[");
    }

    private static String routeOf(String propertyName) {
        String suffix = propertyName.substring(ROUTE_RATIOS.length());
        if (suffix.charAt(0) == '.') {
            return suffix.substring(1);
        }

        if (!suffix.endsWith("]")) {
            throw new TracingSettingsManifestException(
                    "Malformed bracketed route ratio key '%s'".formatted(propertyName)
            );
        }

        return suffix.substring(1, suffix.length() - 1);
    }
}
