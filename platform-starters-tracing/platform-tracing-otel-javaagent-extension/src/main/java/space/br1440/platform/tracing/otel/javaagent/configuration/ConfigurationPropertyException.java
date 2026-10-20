package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.Objects;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;

public final class ConfigurationPropertyException extends RuntimeException {

    private final String propertyName;

    public ConfigurationPropertyException(String propertyName, ConfigurationException failure) {
        super(Objects.requireNonNull(failure, "failure"));
        this.propertyName = Objects.requireNonNull(propertyName, "propertyName");
    }

    public String propertyName() {
        return propertyName;
    }

    public ConfigurationException failure() {
        return (ConfigurationException) getCause();
    }
}
