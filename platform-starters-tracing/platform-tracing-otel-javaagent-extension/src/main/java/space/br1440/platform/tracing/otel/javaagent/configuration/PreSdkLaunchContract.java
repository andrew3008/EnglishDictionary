package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.function.Function;

import io.opentelemetry.javaagent.tooling.config.EarlyInitAgentConfig;

public final class PreSdkLaunchContract {

    private PreSdkLaunchContract() {
    }

    public static void requireSupported() {
        requireSupported(EarlyInitAgentConfig.get()::getString);
    }

    static void requireSupported(Function<String, String> properties) {
        requireAbsent(properties, "otel.config.file");
        requireAbsent(properties, "otel.javaagent.configuration-file");
    }

    private static void requireAbsent(Function<String, String> properties, String propertyName) {
        String value = properties.apply(propertyName);
        if (value != null && !value.isEmpty()) {
            throw new IllegalStateException(propertyName + " is not supported by the controlled agent profile");
        }
    }
}
