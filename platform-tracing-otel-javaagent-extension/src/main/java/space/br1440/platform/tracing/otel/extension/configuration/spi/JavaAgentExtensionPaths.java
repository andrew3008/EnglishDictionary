package space.br1440.platform.tracing.otel.extension.configuration.spi;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

@UtilityClass
public final class JavaAgentExtensionPaths {

    public static String resolveRawValue(ConfigProperties config) {
        String raw = config.getString(ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS);
        if (Strings.isBlank(raw)) {
            raw = System.getProperty(ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS);
        }

        if (Strings.isBlank(raw)) {
            raw = System.getenv(ExtensionEnvironmentVariables.OTEL_JAVAAGENT_EXTENSIONS);
        }

        return raw;
    }
}
