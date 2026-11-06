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
import java.util.Set;
import java.util.TreeSet;
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
        validateAllowlist(BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS, outboundHeaderAllowedKeys);

        this.outboundHeaderPolicy = new BaggageKeyPolicy(
                Set.copyOf(outboundHeaderAllowedKeys),
                compileDenyPatterns(
                        reader,
                        BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS,
                        DEFAULT_BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS
                )
        );

        List<String> spanAttributeAllowedKeys = reader.listValue(
                BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS,
                DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS
        );
        validateAllowlist(BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS, spanAttributeAllowedKeys);

        this.spanAttributesPolicy = new BaggageKeyPolicy(
                Set.copyOf(spanAttributeAllowedKeys),
                compileDenyPatterns(
                        reader,
                        BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS,
                        DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS
                )
        );
    }

    static void validateAllowlist(String propertyName, List<String> keys) {
        TreeSet<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String key : keys) {
            if (!isW3cBaggageToken(key)) {
                throw invalid(
                        propertyName,
                        propertyName + " contains a key that is not a W3C baggage token: " + key
                );
            }

            if (PlatformBaggageKeys.isReserved(key)) {
                throw invalid(
                        propertyName,
                        propertyName + " must not contain reserved key " + key
                );
            }

            if (!seen.add(key)) {
                throw invalid(
                        propertyName,
                        propertyName + " contains keys that differ only by case: " + seen.ceiling(key) + " and " + key
                );
            }
        }
    }

    static boolean isW3cBaggageToken(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }

        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (character <= 0x20 || character >= 0x7F || isSeparator(character)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSeparator(char character) {
        return switch (character) {
            case '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}' -> true;
            default -> false;
        };
    }

    private static ConfigurationPropertyException invalid(String propertyName, String message) {
        return new ConfigurationPropertyException(propertyName, new ConfigurationException(message));
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
