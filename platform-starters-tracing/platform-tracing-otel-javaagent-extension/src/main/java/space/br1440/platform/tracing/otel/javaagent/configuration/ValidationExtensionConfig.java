package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.VALIDATION_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.VALIDATION_MODE;

import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.ValidationMode;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ValidationExtensionConfig {

    private final boolean enabled;
    private final ValidationMode requestedValidationMode;

    ValidationExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(VALIDATION_ENABLED, DEFAULT_ENABLED);
        this.requestedValidationMode = ValidationMode.parse(reader.nullableString(VALIDATION_MODE));
    }
}
