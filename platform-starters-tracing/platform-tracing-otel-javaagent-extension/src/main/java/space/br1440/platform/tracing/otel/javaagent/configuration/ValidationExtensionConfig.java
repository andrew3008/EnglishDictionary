package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL;

import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ValidationExtensionConfig {

    private final boolean enabled;
    private final SpanAttributeValidationLevel requestedLevel;

    ValidationExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(SPAN_ATTRIBUTES_VALIDATION_ENABLED, DEFAULT_ENABLED);
        this.requestedLevel = SpanAttributeValidationLevel.parse(
                reader.nullableString(SPAN_ATTRIBUTES_VALIDATION_LEVEL)
        );
    }
}
