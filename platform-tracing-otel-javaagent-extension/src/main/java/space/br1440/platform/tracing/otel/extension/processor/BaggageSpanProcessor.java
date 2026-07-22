package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BaggageSpanProcessor implements SpanProcessor {

    private static final String ATTRIBUTE_PREFIX = "baggage.";
    private static final String CORRELATION_BAGGAGE_KEY = "platform.correlation.id";

    private final Set<String> allowlist;
    private final List<Pattern> denyPatterns;

    public BaggageSpanProcessor(Set<String> allowlistKeys, List<String> denyPatternStrings) {
        this.allowlist = allowlistKeys.stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        this.denyPatterns = denyPatternStrings.stream()
                .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        if (allowlist.isEmpty()) {
            return;
        }

        AttributesBuilder attributes = Attributes.builder();
        Baggage.fromContext(parentContext)
                .forEach((key, entry) -> copyIfAllowed(key, entry, attributes));
        span.setAllAttributes(attributes.build());
    }

    private void copyIfAllowed(String key, BaggageEntry entry, AttributesBuilder attributes) {
        if (key == null || entry == null) {
            return;
        }

        String normalizedKey = key.toLowerCase(Locale.ROOT);
        if (!allowlist.contains(normalizedKey)) {
            return;
        }

        if (matchesDeny(normalizedKey)) {
            return;
        }

        String value = entry.getValue();
        if (Strings.isBlank(value)) {
            return;
        }

        if (CORRELATION_BAGGAGE_KEY.equals(normalizedKey)) {
            if (isCanonicalCorrelationId(value)) {
                attributes.put(AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID), value);
            }
            return;
        }

        attributes.put(AttributeKey.stringKey(ATTRIBUTE_PREFIX + key), value);
    }

    private static boolean isCanonicalCorrelationId(String value) {
        if (value.length() > 128) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean canonical = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-';
            if (!canonical) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesDeny(String key) {
        for (Pattern pattern : denyPatterns) {
            if (pattern.matcher(key).find()) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
