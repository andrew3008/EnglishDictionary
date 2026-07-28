package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Classifies the provenance of each Category A policy key.
 * <p>
 * AUTHORITATIVE: key originates from an explicitly recognised Config Server property source.
 * LOCAL_OVERRIDE: key originates from command-line, system properties, or local file.
 * UNKNOWN: key not found, or found in an unrecognised source.
 * <p>
 * Unknown source -> {@code UNKNOWN} -> leads to {@link space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyManifestException}
 * (fail-closed). There is no automatic trust grant to unknown sources.
 */
public final class ConfigServerPolicyProvenanceClassifier {

    public enum ProvenanceClass {
        AUTHORITATIVE,
        LOCAL_OVERRIDE,
        UNKNOWN
    }

    private static final java.util.Set<String> LOCAL_OVERRIDE_SOURCE_NAME_FRAGMENTS = java.util.Set.of(
            "commandLineArgs",
            "systemProperties",
            "systemEnvironment",
            "applicationConfig: [file:"
    );

    private static final java.util.Set<String> AUTHORITATIVE_SOURCE_NAME_FRAGMENTS = java.util.Set.of(
            "configserver:",
            "config-server",
            "bootstrapProperties",
            "configClient"
    );

    private final ConfigurableEnvironment environment;

    public ConfigServerPolicyProvenanceClassifier(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    public ProvenanceClass classify(String key) {
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps.containsProperty(key)) {
                String sourceName = ps.getName();
                for (String fragment : LOCAL_OVERRIDE_SOURCE_NAME_FRAGMENTS) {
                    if (sourceName.contains(fragment)) {
                        return ProvenanceClass.LOCAL_OVERRIDE;
                    }
                }
                for (String fragment : AUTHORITATIVE_SOURCE_NAME_FRAGMENTS) {
                    if (sourceName.contains(fragment)) {
                        return ProvenanceClass.AUTHORITATIVE;
                    }
                }
                // Unknown source: fail-closed, not authoritative by default
                return ProvenanceClass.UNKNOWN;
            }
        }
        return ProvenanceClass.UNKNOWN;
    }
}
