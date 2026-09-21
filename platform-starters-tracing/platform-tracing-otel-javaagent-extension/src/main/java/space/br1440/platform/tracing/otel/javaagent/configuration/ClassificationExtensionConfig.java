package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_CLASSIFICATION_NORMAL_THRESHOLD;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_CLASSIFICATION_SLOW_THRESHOLD;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.CLASSIFICATION_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD;

import java.time.Duration;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ClassificationExtensionConfig {

    private final boolean enabled;
    private final Duration slowThreshold;
    private final Duration normalThreshold;

    ClassificationExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(CLASSIFICATION_ENABLED, DEFAULT_ENABLED);
        this.slowThreshold = reader.positiveDuration(CLASSIFICATION_SLOW_THRESHOLD, DEFAULT_CLASSIFICATION_SLOW_THRESHOLD);
        this.normalThreshold = reader.positiveDuration(CLASSIFICATION_NORMAL_THRESHOLD, DEFAULT_CLASSIFICATION_NORMAL_THRESHOLD);

        ExtensionConfigReader.requireOrdered(
                normalThreshold,
                CLASSIFICATION_NORMAL_THRESHOLD,
                slowThreshold,
                CLASSIFICATION_SLOW_THRESHOLD
        );
    }
}
