package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.function.Function;

import space.br1440.platform.common.util.StringUtils;

import io.opentelemetry.javaagent.tooling.config.EarlyInitAgentConfig;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class PreSdkLaunchContract {

    public static void requireSupported() {
        requireSupported(EarlyInitAgentConfig.get()::getString);
    }

    static void requireSupported(Function<String, String> properties) {
        requireAbsent(properties, "otel.config.file");
        requireAbsent(properties, "otel.javaagent.configuration-file");
    }

    private static void requireAbsent(Function<String, String> properties, String propertyName) {
        String value = properties.apply(propertyName);
        if (!StringUtils.isNullOrEmpty(value)) {
            throw new IllegalStateException(propertyName + " is not supported by the controlled agent profile");
        }
    }
}
