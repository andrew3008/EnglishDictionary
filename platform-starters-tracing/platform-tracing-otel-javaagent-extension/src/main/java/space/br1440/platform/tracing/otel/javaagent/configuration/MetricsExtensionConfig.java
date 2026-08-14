package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.METRICS_ENABLED;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class MetricsExtensionConfig {

    private final boolean enabled;

    MetricsExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(METRICS_ENABLED, DEFAULT_ENABLED);
    }
}
