package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_ATTRIBUTE_VALIDATION_LEVEL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL;

import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ValidationExtensionConfig {

    private final SpanAttributeValidationLevel requestedLevel;

    ValidationExtensionConfig(ExtensionConfigReader reader) {
        this.requestedLevel = SpanAttributeValidationLevel.parse(
                reader.stringValue(
                        SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                        DEFAULT_SPAN_ATTRIBUTE_VALIDATION_LEVEL.name()
                )
        );
    }
}
