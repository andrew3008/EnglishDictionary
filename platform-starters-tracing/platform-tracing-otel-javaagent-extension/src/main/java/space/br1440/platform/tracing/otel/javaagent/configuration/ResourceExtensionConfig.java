package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_RESOURCE_VALIDATION_MODE;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.RESOURCE_VALIDATION_MODE;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ResourceExtensionConfig {

    private final ResourceValidationMode validationMode;

    ResourceExtensionConfig(ExtensionConfigReader reader) {
        this.validationMode = reader.enumValue(
                RESOURCE_VALIDATION_MODE,
                ResourceValidationMode.class,
                DEFAULT_RESOURCE_VALIDATION_MODE
        );
    }
}
