package space.br1440.platform.tracing.e2e.configserver.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

public final class ProvenanceInspector {

    private static final String CONFIGURATION_PROPERTIES = "configurationProperties";
    private static final String CONFIG_CLIENT = "configClient";
    private static final String CONFIG_SERVER_PREFIX = "configserver:";

    private ProvenanceInspector() {
    }

    public static KeyEvidence inspect(ConfigurableEnvironment environment, String key) {
        PropertySource<?> effective = findEffectiveSource(environment, key);
        PropertySource<?> remote = findRemoteSource(environment, key);
        Object effectiveValue = effective == null ? null : effective.getProperty(key);
        Object remoteValue = remote == null ? null : remote.getProperty(key);
        boolean localOverride = remote != null && effective != remote;
        return new KeyEvidence(
                key,
                effectiveValue,
                sourceEvidence(effective, key),
                remoteValue,
                sourceEvidence(remote, key),
                classify(environment, effective),
                localOverride,
                localOverride && !Objects.equals(effectiveValue, remoteValue)
        );
    }

    public static MarkerEvidence marker(ConfigurableEnvironment environment) {
        PropertySource<?> marker = environment.getPropertySources().get(CONFIG_CLIENT);
        if (marker == null) {
            return new MarkerEvidence(false, null, null, null);
        }
        return new MarkerEvidence(
                true,
                marker.getClass().getName(),
                marker.getProperty("config.client.version"),
                marker.getProperty("config.client.state")
        );
    }

    public static List<String> sanitizedTree(ConfigurableEnvironment environment, String... keys) {
        List<String> result = new ArrayList<>();
        int index = 0;
        for (PropertySource<?> source : environment.getPropertySources()) {
            List<String> present = new ArrayList<>();
            for (String key : keys) {
                if (source.containsProperty(key)) {
                    present.add(key);
                }
            }
            result.add(index++ + " | " + source.getName() + " | " + source.getClass().getName()
                    + (present.isEmpty() ? "" : " | keys=" + present));
        }
        return List.copyOf(result);
    }

    private static PropertySource<?> findEffectiveSource(ConfigurableEnvironment environment, String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!CONFIGURATION_PROPERTIES.equals(source.getName()) && source.containsProperty(key)) {
                return source;
            }
        }
        return null;
    }

    private static PropertySource<?> findRemoteSource(ConfigurableEnvironment environment, String key) {
        if (environment.getPropertySources().get(CONFIG_CLIENT) == null) {
            return null;
        }
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().startsWith(CONFIG_SERVER_PREFIX)
                    && source instanceof OriginTrackedMapPropertySource
                    && source.containsProperty(key)) {
                return source;
            }
        }
        return null;
    }

    private static SourceEvidence sourceEvidence(PropertySource<?> source, String key) {
        if (source == null) {
            return SourceEvidence.absent();
        }
        Origin origin = OriginLookup.getOrigin(source, key);
        return new SourceEvidence(
                source.getName(),
                source.getClass().getName(),
                Optional.ofNullable(origin).map(value -> value.getClass().getName()).orElse(null),
                Optional.ofNullable(origin).map(Object::toString).orElse(null)
        );
    }

    private static ProvenanceCategory classify(
            ConfigurableEnvironment environment,
            PropertySource<?> source
    ) {
        if (source == null) {
            return ProvenanceCategory.ABSENT;
        }
        if (CONFIG_CLIENT.equals(source.getName())) {
            return ProvenanceCategory.CONFIG_CLIENT_MARKER;
        }
        if (environment.getPropertySources().get(CONFIG_CLIENT) != null
                && source.getName().startsWith(CONFIG_SERVER_PREFIX)
                && source instanceof OriginTrackedMapPropertySource) {
            return ProvenanceCategory.CONFIG_SERVER_REMOTE;
        }
        if ("systemProperties".equals(source.getName()) || "systemEnvironment".equals(source.getName())) {
            return ProvenanceCategory.SYSTEM;
        }
        if ("commandLineArgs".equals(source.getName())) {
            return ProvenanceCategory.COMMAND_LINE;
        }
        if (source.getName().contains("application.yml") || source.getName().contains("application.yaml")) {
            return ProvenanceCategory.LOCAL_YAML;
        }
        return ProvenanceCategory.UNKNOWN_AMBIGUOUS;
    }

    public enum ProvenanceCategory {
        CONFIG_SERVER_REMOTE,
        CONFIG_CLIENT_MARKER,
        SYSTEM,
        COMMAND_LINE,
        LOCAL_YAML,
        UNKNOWN_AMBIGUOUS,
        ABSENT
    }

    public record MarkerEvidence(
            boolean present,
            String sourceClass,
            Object version,
            Object state
    ) {
    }

    public record SourceEvidence(
            String name,
            String sourceClass,
            String originClass,
            String origin
    ) {
        private static SourceEvidence absent() {
            return new SourceEvidence(null, null, null, null);
        }
    }

    public record KeyEvidence(
            String key,
            Object effectiveValue,
            SourceEvidence effectiveSource,
            Object remoteDesiredValue,
            SourceEvidence remoteSource,
            ProvenanceCategory effectiveCategory,
            boolean localOverride,
            boolean conflict
    ) {
    }
}
