package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.util.Set;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

public final class ConfigServerSettingsProvenanceClassifier {

    public enum ProvenanceClass {
        AUTHORITATIVE,
        LOCAL_OVERRIDE,
        UNKNOWN
    }

    private static final Set<String> LOCAL_OVERRIDE_SOURCE_NAME_FRAGMENTS = Set.of(
            "commandLineArgs",
            "systemProperties",
            "systemEnvironment",
            "applicationConfig: [file:"
    );

    private static final Set<String> AUTHORITATIVE_SOURCE_NAME_FRAGMENTS = Set.of(
            "configserver:",
            "config-server",
            "bootstrapProperties",
            "configClient"
    );


    private static final String CONFIGURATION_PROPERTIES_FACADE = "configurationProperties";

    private final ConfigurableEnvironment environment;

    public ConfigServerSettingsProvenanceClassifier(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    public ProvenanceClass classify(String key) {
        String sourceName = originSourceName(key);
        if (sourceName == null) {
            return ProvenanceClass.UNKNOWN;
        }

        for (String fragment : LOCAL_OVERRIDE_SOURCE_NAME_FRAGMENTS) {
            if (sourceName.contains(fragment)) {
                return ProvenanceClass.LOCAL_OVERRIDE;
            }
        }

        if (isNativeConfigServerFileSource(sourceName)) {
            return ProvenanceClass.AUTHORITATIVE;
        }

        for (String fragment : AUTHORITATIVE_SOURCE_NAME_FRAGMENTS) {
            if (sourceName.contains(fragment)) {
                return ProvenanceClass.AUTHORITATIVE;
            }
        }

        return ProvenanceClass.UNKNOWN;
    }

    public String originSourceName(String key) {
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (isBindingFacade(ps)) {
                continue;
            }

            if (ps.containsProperty(key)) {
                return ps.getName();
            }
        }

        return null;
    }

    private static boolean isBindingFacade(PropertySource<?> propertySource) {
        return CONFIGURATION_PROPERTIES_FACADE.equals(propertySource.getName());
    }

    private static boolean isNativeConfigServerFileSource(String sourceName) {
        return sourceName.startsWith("file:");
    }
}
