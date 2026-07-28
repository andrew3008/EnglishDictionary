package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

final class ConfigServerPolicyManifestBuilder {

    static final String CONFIG_IMPORT = "spring.config.import";
    static final String SAMPLING_ENABLED = "platform.tracing.policy.sampling.enabled";
    static final String SAMPLING_RATIO = "platform.tracing.policy.sampling.ratio";
    static final String SAMPLING_ROUTE_RATIOS = "platform.tracing.policy.sampling.route-ratios";
    static final String VALIDATION_ENABLED = "platform.tracing.policy.validation.enabled";
    static final String VALIDATION_STRICT = "platform.tracing.policy.validation.strict";
    static final String EXPORTER_ENABLED = "platform.tracing.policy.exporter.enabled";
    static final String TRACE_CONTROL_HEADERS_ENABLED =
            "platform.tracing.policy.propagation.trace-control-headers.enabled";
    static final String DIAGNOSTICS_LOG_LEVEL =
            "platform.tracing.policy.diagnostics.log-level";

    private final ConfigServerProvenanceClassifier classifier;

    ConfigServerPolicyManifestBuilder(ConfigServerProvenanceClassifier classifier) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    CompleteManifest build(ConfigurableEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        if (classifier.hasLegacyBootstrap(environment)) {
            throw new TracingPolicyManifestException(
                    "Legacy Spring Cloud Config bootstrap is not supported; use spring.config.import=configserver:");
        }
        requireNonOptionalConfigServerImport(environment);
        ConfigServerProvenanceClassifier.ConfigServerMarker marker =
                classifier.inspectMarker(environment);
        if (!marker.present()) {
            throw new TracingPolicyManifestException("Config Server fetch marker configClient is missing");
        }

        CompleteTracingPolicySnapshot policy;
        try {
            policy = new CompleteTracingPolicySnapshot(
                    booleanValue(authoritative(environment, SAMPLING_ENABLED)),
                    doubleValue(authoritative(environment, SAMPLING_RATIO), SAMPLING_RATIO),
                    routeRatios(environment),
                    booleanValue(authoritative(environment, VALIDATION_ENABLED)),
                    booleanValue(authoritative(environment, VALIDATION_STRICT)),
                    booleanValue(authoritative(environment, EXPORTER_ENABLED)),
                    booleanValue(authoritative(environment, TRACE_CONTROL_HEADERS_ENABLED)),
                    logLevel(authoritative(environment, DIAGNOSTICS_LOG_LEVEL)));
        } catch (IllegalArgumentException exception) {
            throw new TracingPolicyManifestException(
                    "Invalid complete tracing policy manifest: " + exception.getMessage(),
                    exception);
        }
        return new CompleteManifest(policy, marker.sourceRevision(), marker.sourceState());
    }

    private static void requireNonOptionalConfigServerImport(
            ConfigurableEnvironment environment) {
        String imports = environment.getProperty(CONFIG_IMPORT);
        if (imports == null
                || Arrays.stream(imports.split(","))
                        .map(String::trim)
                        .noneMatch(value -> value.startsWith("configserver:"))) {
            throw new TracingPolicyManifestException(
                    "CONFIG_SERVER authority requires non-optional spring.config.import=configserver:");
        }
    }

    private Object authoritative(ConfigurableEnvironment environment, String key) {
        ConfigServerProvenanceClassifier.KeyProvenance evidence =
                classifier.inspect(environment, key);
        if (!evidence.isAuthoritativeRemote()) {
            throw new TracingPolicyManifestException(
                    "Category A key is missing or not authoritative Config Server data: " + key);
        }
        return evidence.remoteDesiredValue();
    }

    private Map<String, Double> routeRatios(ConfigurableEnvironment environment) {
        ConfigServerProvenanceClassifier.KeyProvenance direct =
                classifier.inspect(environment, SAMPLING_ROUTE_RATIOS);
        if (direct.isAuthoritativeRemote()) {
            return mapValue(direct.remoteDesiredValue());
        }

        Map<String, Double> routes = new TreeMap<>();
        boolean found = false;
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)
                    || !source.getName().startsWith(
                            ConfigServerProvenanceClassifier.CONFIG_SERVER_PREFIX)) {
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                String route = routeFromDescendantKey(key);
                if (route == null) {
                    continue;
                }
                found = true;
                Object value = authoritative(environment, key);
                Double previous = routes.put(route, doubleValue(value, key));
                if (previous != null) {
                    throw new TracingPolicyManifestException(
                            "Duplicate normalized route ratio: " + route);
                }
            }
        }
        if (!found) {
            throw new TracingPolicyManifestException(
                    "Category A key is missing or not authoritative Config Server data: "
                            + SAMPLING_ROUTE_RATIOS);
        }
        return routes;
    }

    private static Map<String, Double> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(SAMPLING_ROUTE_RATIOS + " must be a map");
        }
        Map<String, Double> routes = new LinkedHashMap<>();
        raw.forEach((key, ratio) -> {
            if (!(key instanceof String route)) {
                throw new IllegalArgumentException("route ratio key must be a string");
            }
            routes.put(route, doubleValue(ratio, SAMPLING_ROUTE_RATIOS + "[" + route + "]"));
        });
        return routes;
    }

    private static String routeFromDescendantKey(String key) {
        String bracketPrefix = SAMPLING_ROUTE_RATIOS + "[";
        if (key.startsWith(bracketPrefix) && key.endsWith("]")) {
            return key.substring(bracketPrefix.length(), key.length() - 1);
        }
        String dotPrefix = SAMPLING_ROUTE_RATIOS + ".";
        if (key.startsWith(dotPrefix)) {
            return key.substring(dotPrefix.length());
        }
        return null;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw new IllegalArgumentException("boolean value expected");
    }

    private static double doubleValue(Object value, String key) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(key + " must be a double", exception);
            }
        }
        throw new IllegalArgumentException(key + " must be a double");
    }

    private static OtelAgentLogLevel logLevel(Object value) {
        if (!(value instanceof String text) || !text.equals(text.trim())) {
            throw new IllegalArgumentException(DIAGNOSTICS_LOG_LEVEL + " must be a trimmed string");
        }
        return OtelAgentLogLevel.valueOf(text.toUpperCase(Locale.ROOT));
    }

    record CompleteManifest(
            CompleteTracingPolicySnapshot policy,
            String sourceRevision,
            String sourceState) {
    }
}
