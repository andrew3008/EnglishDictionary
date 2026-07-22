package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.*;

@Getter
@Accessors(fluent = true)
public final class MetricsExtensionConfig {

    private final boolean enabled;

    MetricsExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(METRICS_ENABLED, DEFAULT_ENABLED);
    }
}
