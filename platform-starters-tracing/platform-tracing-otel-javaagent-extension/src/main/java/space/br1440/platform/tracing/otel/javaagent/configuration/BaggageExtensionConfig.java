package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_PROPAGATION_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_PROPAGATION_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS;

import java.util.List;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class BaggageExtensionConfig {

    private final boolean propagationEnabled;
    private final List<String> outboundHeaderAllowedKeys;
    private final List<String> outboundHeaderDenyPatterns;
    private final List<String> spanAttributesAllowedKeys;
    private final List<String> spanAttributesDenyPatterns;

    BaggageExtensionConfig(ExtensionConfigReader reader) {
        this.propagationEnabled = reader.booleanValue(
                BAGGAGE_PROPAGATION_ENABLED, DEFAULT_BAGGAGE_PROPAGATION_ENABLED
        );

        this.outboundHeaderAllowedKeys = reader.listValue(
                BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS,
                DEFAULT_BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS
        );

        this.outboundHeaderDenyPatterns = reader.listValue(
                BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS,
                DEFAULT_BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS
        );

        this.spanAttributesAllowedKeys = reader.listValue(
                BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS,
                DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS
        );

        this.spanAttributesDenyPatterns = reader.listValue(
                BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS,
                DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS
        );
    }
}
