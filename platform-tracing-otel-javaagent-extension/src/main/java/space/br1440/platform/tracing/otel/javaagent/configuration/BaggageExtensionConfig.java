package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.*;

@Getter
@Accessors(fluent = true)
public final class BaggageExtensionConfig {

    private final boolean enabled;
    private final List<String> allowlistKeys;
    private final List<String> denyPatterns;

    BaggageExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(BAGGAGE_ENABLED, DEFAULT_BAGGAGE_ENABLED);
        this.allowlistKeys = reader.listValue(BAGGAGE_ALLOWLIST_KEYS, DEFAULT_BAGGAGE_ALLOWLIST);
        this.denyPatterns = reader.listValue(BAGGAGE_DENY_PATTERNS, DEFAULT_BAGGAGE_DENY_PATTERNS);
    }
}
