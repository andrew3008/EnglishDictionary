package space.br1440.platform.tracing.otel.extension.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames.*;

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
