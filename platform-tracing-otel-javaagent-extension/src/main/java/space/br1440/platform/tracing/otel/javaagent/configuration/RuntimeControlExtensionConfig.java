package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.CONTROL_RUNTIME_MUTATION_ENABLED;

/**
 * Startup-настройки unified runtime-control protocol.
 */
@Getter
@Accessors(fluent = true)
public final class RuntimeControlExtensionConfig {

    private final boolean runtimeMutationEnabled;

    RuntimeControlExtensionConfig(ExtensionConfigReader reader) {
        this.runtimeMutationEnabled = reader.booleanValue(CONTROL_RUNTIME_MUTATION_ENABLED, false);
    }
}
