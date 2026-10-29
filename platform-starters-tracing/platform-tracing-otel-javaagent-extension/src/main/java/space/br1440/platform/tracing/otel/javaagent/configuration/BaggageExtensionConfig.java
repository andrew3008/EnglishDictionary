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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import space.br1440.platform.tracing.api.propagation.PlatformBaggageKeys;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class BaggageExtensionConfig {

    private final boolean propagationEnabled;
    private final BaggageKeyPolicy outboundHeaderPolicy;
    private final BaggageKeyPolicy spanAttributesPolicy;

    BaggageExtensionConfig(ExtensionConfigReader reader) {
        this.propagationEnabled = reader.booleanValue(
                BAGGAGE_PROPAGATION_ENABLED,
                DEFAULT_BAGGAGE_PROPAGATION_ENABLED
        );

        List<String> outboundHeaderAllowedKeys = reader.listValue(
                BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS,
                DEFAULT_BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS
        );

        boolean containsCorrelationId = outboundHeaderAllowedKeys.stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .anyMatch(PlatformBaggageKeys.CORRELATION_ID::equals);
        if (containsCorrelationId) {
            String message = "%s must not contain reserved key %s"
                    .formatted(BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS, PlatformBaggageKeys.CORRELATION_ID);

            throw new ConfigurationPropertyException(
                    BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS,
                    new ConfigurationException(message)
            );
        }

        this.outboundHeaderPolicy = new BaggageKeyPolicy(
                Set.copyOf(outboundHeaderAllowedKeys),
                compileDenyPatterns(
                        reader,
                        BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS,
                        DEFAULT_BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS
                )
        );

        this.spanAttributesPolicy = new BaggageKeyPolicy(
                Set.copyOf(
                        reader.listValue(
                                BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS,
                                DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS
                        )
                ),
                compileDenyPatterns(
                        reader,
                        BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS,
                        DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS
                )
        );
    }

    private static List<Pattern> compileDenyPatterns(ExtensionConfigReader reader,
                                                     String propertyName,
                                                     List<String> defaultPatterns) {
        try {
            return reader.listValue(propertyName, defaultPatterns).stream()
                    .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
                    .toList();
        } catch (PatternSyntaxException failure) {
            throw new ConfigurationPropertyException(
                    propertyName,
                    new ConfigurationException(propertyName + " contains an invalid regular expression", failure)
            );
        }
    }
}
