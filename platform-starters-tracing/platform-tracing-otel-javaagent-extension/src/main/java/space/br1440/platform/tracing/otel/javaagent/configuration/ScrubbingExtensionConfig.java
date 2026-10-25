package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BUILT_IN_RULES;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SCRUBBING_MISSING_KEY_POLICY;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SCRUBBING_BUILT_IN_RULES;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SCRUBBING_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SCRUBBING_HMAC_KEY;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SCRUBBING_MISSING_KEY_POLICY;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SCRUBBING_RULES_CONFIG;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SCRUBBING_RULES_EXTENSIONS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SCRUBBING_RULES_VALIDATION_MODE;

import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ScrubbingExtensionConfig {

    private final boolean enabled;
    private final List<String> builtInRules;
    private final MaskedSecret hmacKey;
    private final ScrubbingMissingKeyPolicy missingKeyPolicy;
    private final String rulesConfig;
    private final String rulesExtensions;
    private final ExtensionRuleLoader.ValidationMode rulesValidationMode;

    ScrubbingExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(SCRUBBING_ENABLED, DEFAULT_ENABLED);
        this.builtInRules = reader.listValue(SCRUBBING_BUILT_IN_RULES, DEFAULT_BUILT_IN_RULES);
        this.hmacKey = MaskedSecret.of(reader.nullableString(SCRUBBING_HMAC_KEY));
        this.missingKeyPolicy = reader.enumValue(
                SCRUBBING_MISSING_KEY_POLICY,
                ScrubbingMissingKeyPolicy.class,
                DEFAULT_SCRUBBING_MISSING_KEY_POLICY
        );
        this.rulesConfig = reader.nullableString(SCRUBBING_RULES_CONFIG);
        this.rulesExtensions = reader.nullableString(SCRUBBING_RULES_EXTENSIONS);
        this.rulesValidationMode = ExtensionRuleLoader.ValidationMode.parse(
                reader.nullableString(SCRUBBING_RULES_VALIDATION_MODE)
        );
    }
}
