package space.br1440.platform.tracing.otel.extension.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames.*;

@Getter
@Accessors(fluent = true)
public final class ResourceExtensionConfig {

    private final String policyVersion;
    private final boolean normalizeEnvironment;
    private final String validationMode;
    private final boolean detectContainerId;

    ResourceExtensionConfig(ExtensionConfigReader reader) {
        this.policyVersion = reader.stringValue(RESOURCE_POLICY_VERSION, DEFAULT_RESOURCE_POLICY_VERSION);
        this.normalizeEnvironment = reader.booleanValue(RESOURCE_NORMALIZE_ENVIRONMENT, DEFAULT_RESOURCE_NORMALIZE_ENVIRONMENT);
        this.validationMode = reader.stringValue(RESOURCE_VALIDATION_MODE, DEFAULT_RESOURCE_VALIDATION_MODE);
        this.detectContainerId = reader.booleanValue(RESOURCE_DETECT_CONTAINER_ID, DEFAULT_RESOURCE_DETECT_CONTAINER_ID);
    }
}
