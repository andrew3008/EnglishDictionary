package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

final class ConfigServerProvenanceClassifier {

    static final String CONFIG_CLIENT = "configClient";
    static final String CONFIG_SERVER_PREFIX = "configserver:";
    private static final String CONFIGURATION_PROPERTIES = "configurationProperties";

    ConfigServerMarker inspectMarker(ConfigurableEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        PropertySource<?> marker = environment.getPropertySources().get(CONFIG_CLIENT);
        if (!(marker instanceof MapPropertySource mapMarker)) {
            return ConfigServerMarker.absent();
        }
        return new ConfigServerMarker(
                true,
                optionalText(mapMarker.getProperty("config.client.version")),
                optionalText(mapMarker.getProperty("config.client.state")));
    }

    KeyProvenance inspect(ConfigurableEnvironment environment, String key) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(key, "key");
        PropertySource<?> effective = findEffectiveSource(environment, key);
        PropertySource<?> remote = findRemoteSource(environment, key);
        Object effectiveValue = value(effective, key);
        Object remoteValue = value(remote, key);
        boolean localOverride = remote != null && effective != remote;
        return new KeyProvenance(
                key,
                effectiveValue,
                evidence(effective, key),
                remoteValue,
                evidence(remote, key),
                classify(environment, effective),
                localOverride,
                localOverride && !Objects.equals(effectiveValue, remoteValue));
    }

    boolean hasLegacyBootstrap(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            String name = source.getName();
            if ("configService".equals(name)
                    || name.startsWith("bootstrapProperties-")
                    || name.startsWith("configService:")) {
                return true;
            }
        }
        return false;
    }

    private static PropertySource<?> findEffectiveSource(
            ConfigurableEnvironment environment,
            String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!CONFIGURATION_PROPERTIES.equals(source.getName())
                    && source.containsProperty(key)) {
                return source;
            }
        }
        return null;
    }

    private static PropertySource<?> findRemoteSource(
            ConfigurableEnvironment environment,
            String key) {
        if (environment.getPropertySources().get(CONFIG_CLIENT) == null) {
            return null;
        }
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (isRemote(source) && source.containsProperty(key)) {
                return source;
            }
        }
        return null;
    }

    private static boolean isRemote(PropertySource<?> source) {
        return source.getName().startsWith(CONFIG_SERVER_PREFIX)
                && source instanceof OriginTrackedMapPropertySource;
    }

    private static PropertySourceCategory classify(
            ConfigurableEnvironment environment,
            PropertySource<?> source) {
        if (source == null) {
            return PropertySourceCategory.ABSENT;
        }
        if (CONFIG_CLIENT.equals(source.getName())) {
            return PropertySourceCategory.CONFIG_CLIENT_MARKER;
        }
        if (environment.getPropertySources().get(CONFIG_CLIENT) != null && isRemote(source)) {
            return PropertySourceCategory.CONFIG_SERVER_REMOTE;
        }
        return switch (source.getName()) {
            case "systemProperties", "systemEnvironment" -> PropertySourceCategory.SYSTEM;
            case "commandLineArgs" -> PropertySourceCategory.COMMAND_LINE;
            default -> isLocalYaml(source)
                    ? PropertySourceCategory.LOCAL_YAML
                    : PropertySourceCategory.UNKNOWN_AMBIGUOUS;
        };
    }

    private static boolean isLocalYaml(PropertySource<?> source) {
        String name = source.getName();
        return name.contains("application.yml")
                || name.contains("application.yaml")
                || name.contains("application.properties");
    }

    private static Object value(PropertySource<?> source, String key) {
        return source == null ? null : source.getProperty(key);
    }

    private static SourceEvidence evidence(PropertySource<?> source, String key) {
        if (source == null) {
            return SourceEvidence.absent();
        }
        Origin origin = OriginLookup.getOrigin(source, key);
        return new SourceEvidence(
                source.getName(),
                source.getClass().getName(),
                Optional.ofNullable(origin).map(value -> value.getClass().getName()).orElse(null),
                Optional.ofNullable(origin).map(Object::toString).orElse(null));
    }

    private static String optionalText(Object value) {
        return value == null ? "" : value.toString();
    }

    record ConfigServerMarker(boolean present, String sourceRevision, String sourceState) {

        private static ConfigServerMarker absent() {
            return new ConfigServerMarker(false, "", "");
        }
    }

    record SourceEvidence(
            String name,
            String sourceClass,
            String originClass,
            String origin) {

        private static SourceEvidence absent() {
            return new SourceEvidence(null, null, null, null);
        }
    }

    record KeyProvenance(
            String key,
            Object effectiveValue,
            SourceEvidence effectiveSource,
            Object remoteDesiredValue,
            SourceEvidence remoteSource,
            PropertySourceCategory effectiveCategory,
            boolean localOverride,
            boolean conflict) {

        boolean isAuthoritativeRemote() {
            return effectiveCategory == PropertySourceCategory.CONFIG_SERVER_REMOTE
                    && remoteSource.name() != null
                    && Objects.equals(effectiveSource.name(), remoteSource.name())
                    && !localOverride
                    && !conflict;
        }
    }
}
