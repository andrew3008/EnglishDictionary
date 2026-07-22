package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Duration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.*;

@Getter
@Accessors(fluent = true)
public final class ClassificationExtensionConfig {

    private final boolean enabled;
    private final Duration slowThreshold;
    private final Duration normalThreshold;

    ClassificationExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(CLASSIFICATION_ENABLED, DEFAULT_ENABLED);
        this.slowThreshold = reader.durationValue(CLASSIFICATION_SLOW_THRESHOLD, DEFAULT_CLASSIFICATION_SLOW_THRESHOLD);
        this.normalThreshold = reader.durationValue(CLASSIFICATION_NORMAL_THRESHOLD, DEFAULT_CLASSIFICATION_NORMAL_THRESHOLD);
    }
}
