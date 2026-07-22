package space.br1440.platform.tracing.otel.javaagent.configuration;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ExtensionConfigReader {

    private final ConfigProperties config;

    ExtensionConfigReader(ConfigProperties config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    boolean booleanValue(String name, boolean defaultValue) {
        Boolean configValue = config.getBoolean(name);
        return (configValue == null) ? defaultValue : configValue;
    }

    @SuppressWarnings("SameParameterValue")
    double doubleValue(String name, double defaultValue) {
        Double configValue = config.getDouble(name);
        return (configValue == null) ? defaultValue : configValue;
    }

    String stringValue(String name, String defaultValue) {
        String configValue = config.getString(name);
        return (configValue == null) ? defaultValue : configValue;
    }

    String nullableString(String name) {
        return config.getString(name);
    }

    List<String> listValue(String name, List<String> defaultValue) {
        List<String> configValue = config.getList(name);
        if (configValue == null) {
            return defaultValue;
        }

        if (configValue.isEmpty() && (config.getString(name) == null)) {
            return defaultValue;
        }

        return List.copyOf(configValue);
    }

    @SuppressWarnings("SameParameterValue")
    Map<String, String> mapValue(String name, Map<String, String> defaultValue) {
        Map<String, String> configValue = config.getMap(name);
        return (configValue == null) ? defaultValue : configValue;
    }

    Duration durationValue(String name, Duration defaultValue) {
        Duration configValue = config.getDuration(name);
        return (configValue == null) ? defaultValue : configValue;
    }
}