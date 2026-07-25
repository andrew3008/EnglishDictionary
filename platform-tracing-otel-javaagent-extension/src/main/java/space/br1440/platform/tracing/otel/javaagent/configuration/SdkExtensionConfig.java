package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SDK_MODE;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class SdkExtensionConfig {

    private final String mode;

    SdkExtensionConfig(ExtensionConfigReader reader) {
        this.mode = reader.nullableString(SDK_MODE);
    }
}
