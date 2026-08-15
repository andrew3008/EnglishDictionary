package space.br1440.platform.tracing.otel.javaagent.configuration;

import space.br1440.platform.common.util.StringUtils;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class JavaAgentExtensionPaths {

    public static String resolveRawValue(ConfigProperties config) {
        String raw = config.getString(ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS);
        if (StringUtils.isNullOrBlank(raw)) {
            raw = System.getProperty(ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS);
        }

        if (StringUtils.isNullOrBlank(raw)) {
            raw = System.getenv(ExtensionEnvironmentVariables.OTEL_JAVAAGENT_EXTENSIONS);
        }

        return raw;
    }
}
