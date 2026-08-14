package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_REMOTE_SERVICE_PRIORITY;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.ENRICHING_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.ENRICHING_REMOTE_SERVICE_PRIORITY;

import java.util.List;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class EnrichingExtensionConfig {

    private final boolean enabled;
    private final List<String> remoteServicePriority;

    EnrichingExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(ENRICHING_ENABLED, DEFAULT_ENABLED);
        this.remoteServicePriority = reader.listValue(ENRICHING_REMOTE_SERVICE_PRIORITY, DEFAULT_REMOTE_SERVICE_PRIORITY);
    }
}
