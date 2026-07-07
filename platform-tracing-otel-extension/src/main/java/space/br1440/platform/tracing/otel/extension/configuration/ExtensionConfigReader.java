package space.br1440.platform.tracing.otel.extension.configuration;

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
        Boolean v = config.getBoolean(name);
        return (v != null) ? v : defaultValue;
    }

    @SuppressWarnings("SameParameterValue")
    double doubleValue(String name, double defaultValue) {
        Double v = config.getDouble(name);
        return (v != null) ? v : defaultValue;
    }

    String stringValue(String name, String defaultValue) {
        String v = config.getString(name);
        return (v != null) ? v : defaultValue;
    }

    String nullableString(String name) {
        return config.getString(name);
    }

    List<String> listValue(String name, List<String> defaultValue) {
        List<String> v = config.getList(name);
        return (v != null) ? List.copyOf(v) : defaultValue;
    }

    @SuppressWarnings("SameParameterValue")
    Map<String, String> mapValue(String name, Map<String, String> defaultValue) {
        Map<String, String> v = config.getMap(name);
        return (v != null) ? Map.copyOf(v) : defaultValue;
    }

    Duration durationValue(String name, Duration defaultValue) {
        Duration v = config.getDuration(name);
        return (v != null) ? v : defaultValue;
    }
}
