package space.br1440.platform.tracing.otel.javaagent.configuration;

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
        this.enabled = reader.booleanValue(
                ExtensionPropertyNames.CLASSIFICATION_ENABLED,
                ExtensionDefaults.DEFAULT_ENABLED
        );

        this.slowThreshold = requirePositive(
                reader.durationValue(
                        ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD,
                        ExtensionDefaults.DEFAULT_CLASSIFICATION_SLOW_THRESHOLD
                ),
                ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD
        );

        this.normalThreshold = requirePositive(
                reader.durationValue(
                        ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD,
                        ExtensionDefaults.DEFAULT_CLASSIFICATION_NORMAL_THRESHOLD
                ),
                ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD
        );

        if (slowThreshold.compareTo(normalThreshold) < 0) {
            throw new IllegalArgumentException(
                    ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD + " must be greater than or equal to "
                            + ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD
            );
        }
    }

    private static Duration requirePositive(Duration value, String propertyName) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }

        return value;
    }
}
