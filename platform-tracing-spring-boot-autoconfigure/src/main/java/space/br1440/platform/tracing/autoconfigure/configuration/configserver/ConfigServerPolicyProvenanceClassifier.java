package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Classifies the provenance of each Category A policy key.
 * <p>
 * AUTHORITATIVE: key originates from a Config Server property source.
 * LOCAL_OVERRIDE: key originates from command-line args, system properties, or local file.
 * UNKNOWN: key not present.
 */
public final class ConfigServerPolicyProvenanceClassifier {

    public enum ProvenanceClass {
        AUTHORITATIVE,
        LOCAL_OVERRIDE,
        UNKNOWN
    }

    private static final java.util.Set<String> LOCAL_OVERRIDE_SOURCE_PREFIXES = java.util.Set.of(
            "commandLineArgs",
            "systemProperties",
            "systemEnvironment",
            "applicationConfig: [file:"
    );

    private final ConfigurableEnvironment environment;

    public ConfigServerPolicyProvenanceClassifier(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    public ProvenanceClass classify(String key) {
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps.containsProperty(key)) {
                String sourceName = ps.getName();
                for (String localPrefix : LOCAL_OVERRIDE_SOURCE_PREFIXES) {
                    if (sourceName.startsWith(localPrefix) || sourceName.contains(localPrefix)) {
                        return ProvenanceClass.LOCAL_OVERRIDE;
                    }
                }
                // Config Server sources contain "configserver" or "bootstrapProperties"
                if (sourceName.contains("configserver") || sourceName.contains("config-server")
                        || sourceName.contains("bootstrapProperties")) {
                    return ProvenanceClass.AUTHORITATIVE;
                }
                // Default: treat as authoritative if not clearly local
                return ProvenanceClass.AUTHORITATIVE;
            }
        }
        return ProvenanceClass.UNKNOWN;
    }
}
