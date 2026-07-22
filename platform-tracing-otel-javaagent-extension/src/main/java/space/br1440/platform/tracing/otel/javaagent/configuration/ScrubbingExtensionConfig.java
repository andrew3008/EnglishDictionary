package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.*;

@Getter
@Accessors(fluent = true)
public final class ScrubbingExtensionConfig {

    private final boolean enabled;
    private final List<String> builtInRules;
    private final String hmacKey;
    private final String missingKeyPolicy;
    private final String hashKeyId;
    private final String rulesConfig;
    private final String rulesExtensions;
    private final String rulesValidationMode;

    ScrubbingExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(SCRUBBING_ENABLED, DEFAULT_ENABLED);
        this.builtInRules = reader.listValue(SCRUBBING_BUILT_IN_RULES, DEFAULT_BUILT_IN_RULES);
        this.hmacKey = reader.nullableString(SCRUBBING_HMAC_KEY);
        this.missingKeyPolicy = reader.stringValue(SCRUBBING_MISSING_KEY_POLICY, DEFAULT_SCRUBBING_MISSING_KEY_POLICY);
        this.hashKeyId = reader.nullableString(SCRUBBING_HASH_KEY_ID);
        this.rulesConfig = reader.nullableString(SCRUBBING_RULES_CONFIG);
        this.rulesExtensions = reader.nullableString(SCRUBBING_RULES_EXTENSIONS);
        this.rulesValidationMode = reader.stringValue(SCRUBBING_RULES_VALIDATION_MODE, DEFAULT_SCRUBBING_VALIDATION_MODE);
    }
}
