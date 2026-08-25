package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.PLATFORM_ENVIRONMENT;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.VALIDATION_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.VALIDATION_MODE;

import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.PlatformEnvironment;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.ValidationMode;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ValidationExtensionConfig {

    private final boolean enabled;
    private final PlatformEnvironment platformEnvironment;
    private final ValidationMode requestedValidationMode;

    ValidationExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(VALIDATION_ENABLED, DEFAULT_ENABLED);
        this.platformEnvironment = PlatformEnvironment.parse(reader.nullableString(PLATFORM_ENVIRONMENT));
        this.requestedValidationMode = ValidationMode.parse(reader.nullableString(VALIDATION_MODE));
    }
}
