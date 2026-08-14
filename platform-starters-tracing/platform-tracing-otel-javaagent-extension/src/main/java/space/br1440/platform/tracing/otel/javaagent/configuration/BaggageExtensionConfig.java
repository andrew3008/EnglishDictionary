package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_PROCESSOR_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_PROCESSOR_DENY_PATTERNS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_PROCESSOR_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_PROPAGATION_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_PROPAGATION_OUTBOUND_DENY_PATTERNS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_PROCESSOR_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_PROCESSOR_DENY_PATTERNS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_PROCESSOR_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_PROPAGATION_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_PROPAGATION_OUTBOUND_DENY_PATTERNS;

import java.util.List;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class BaggageExtensionConfig {

    private final boolean propagationEnabled;
    private final List<String> propagationOutboundAllowedKeys;
    private final List<String> propagationOutboundDenyPatterns;

    private final boolean processorEnabled;
    private final List<String> processorAllowedKeys;
    private final List<String> processorDenyPatterns;

    BaggageExtensionConfig(ExtensionConfigReader reader) {
        this.propagationEnabled = reader.booleanValue(
                BAGGAGE_PROPAGATION_ENABLED, DEFAULT_BAGGAGE_PROPAGATION_ENABLED
        );

        this.propagationOutboundAllowedKeys = reader.listValue(
                BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS, DEFAULT_BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS
        );

        this.propagationOutboundDenyPatterns = reader.listValue(
                BAGGAGE_PROPAGATION_OUTBOUND_DENY_PATTERNS, DEFAULT_BAGGAGE_PROPAGATION_OUTBOUND_DENY_PATTERNS
        );

        this.processorEnabled = reader.booleanValue(
                BAGGAGE_PROCESSOR_ENABLED, DEFAULT_BAGGAGE_PROCESSOR_ENABLED
        );

        this.processorAllowedKeys = reader.listValue(
                BAGGAGE_PROCESSOR_ALLOWED_KEYS, DEFAULT_BAGGAGE_PROCESSOR_ALLOWED_KEYS
        );

        this.processorDenyPatterns = reader.listValue(
                BAGGAGE_PROCESSOR_DENY_PATTERNS, DEFAULT_BAGGAGE_PROCESSOR_DENY_PATTERNS
        );
    }
}
