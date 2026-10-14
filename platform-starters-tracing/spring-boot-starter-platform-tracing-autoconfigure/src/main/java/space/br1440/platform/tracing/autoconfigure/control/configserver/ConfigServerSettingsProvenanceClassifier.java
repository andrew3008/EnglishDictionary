package space.br1440.platform.tracing.autoconfigure.control.configserver;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import space.br1440.platform.tracing.autoconfigure.internal.WinningPropertySourceTraversal;

public final class ConfigServerSettingsProvenanceClassifier {

    public enum ProvenanceClass {
        AUTHORITATIVE,
        LOCAL_OVERRIDE,
        UNKNOWN
    }

    private final ConfigurableEnvironment environment;

    public ConfigServerSettingsProvenanceClassifier(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    public ProvenanceClass classify(String key) {
        String sourceName = originSourceName(key);
        if (sourceName == null) {
            return ProvenanceClass.UNKNOWN;
        }

        if (sourceName.startsWith("configserver:")) {
            return ProvenanceClass.AUTHORITATIVE;
        }

        if (isLocalOverride(sourceName)) {
            return ProvenanceClass.LOCAL_OVERRIDE;
        }

        return ProvenanceClass.UNKNOWN;
    }

    public String originSourceName(String key) {
        PropertySource<?> winner = WinningPropertySourceTraversal.findWinningLeaf(environment, key);
        return (winner == null) ? null : winner.getName();
    }

    private static boolean isLocalOverride(String sourceName) {
        return "commandLineArgs".equals(sourceName)
                || "systemProperties".equals(sourceName)
                || "systemEnvironment".equals(sourceName)
                || sourceName.startsWith("Config resource '");
    }
}
