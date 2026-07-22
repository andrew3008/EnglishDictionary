package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.*;

@Getter
@Accessors(fluent = true)
public final class QueueExtensionConfig {

    private final String overflowPolicy;

    QueueExtensionConfig(ExtensionConfigReader reader) {
        this.overflowPolicy = reader.stringValue(QUEUE_OVERFLOW_POLICY, DEFAULT_QUEUE_OVERFLOW_POLICY);
    }
}
