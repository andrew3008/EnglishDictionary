package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class QueueExtensionConfig {

    private final QueueOverflowPolicy overflowPolicy;

    QueueExtensionConfig(ExtensionConfigReader reader) {
        this.overflowPolicy = reader.enumValue(
                QUEUE_OVERFLOW_POLICY,
                QueueOverflowPolicy.class,
                DEFAULT_QUEUE_OVERFLOW_POLICY
        );
    }
}
