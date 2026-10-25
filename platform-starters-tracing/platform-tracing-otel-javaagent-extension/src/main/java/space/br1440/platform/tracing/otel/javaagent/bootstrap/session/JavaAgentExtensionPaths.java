package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.experimental.UtilityClass;

@UtilityClass
final class JavaAgentExtensionPaths {

    private static final String OTEL_JAVAAGENT_EXTENSIONS_ENV = "OTEL_JAVAAGENT_EXTENSIONS";

    static String resolveRawValue(ConfigProperties config) {
        String raw = config.getString(ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS);

        if (StringUtils.isNullOrEmpty(raw)) {
            raw = System.getProperty(ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS);
        }

        if (StringUtils.isNullOrEmpty(raw)) {
            raw = System.getenv(OTEL_JAVAAGENT_EXTENSIONS_ENV);
        }

        return raw;
    }
}
