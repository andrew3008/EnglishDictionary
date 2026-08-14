package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Копирует разрешённые baggage-ключи в span attributes при {@code onStart}.
 * <p>
 * Allowlist и deny-паттерны фиксируются на старте (Category B, immutable) — runtime mutation
 * отсутствует. Ключ {@code platform.correlation.id} обрабатывается отдельно: маппится на
 * {@link PlatformAttributes#PLATFORM_CORRELATION_ID} только если значение проходит canonical-id
 * валидацию ({@link #isCanonicalCorrelationId}), защищая от записи произвольных строк под
 * зарезервированный platform-атрибут.
 */
public final class BaggageSpanProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

    private static final String DIAGNOSTIC_ID = "baggage";

    private static final String ATTRIBUTE_PREFIX = "baggage.";
    private static final String CORRELATION_BAGGAGE_KEY = "platform.correlation.id";
    private static final int CORRELATION_ID_MAX_LENGTH = 128;
    private static final AttributeKey<String> CORRELATION_ATTRIBUTE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID);

    private final Set<String> allowlist;
    private final List<Pattern> denyPatterns;

    private final Map<String, AttributeKey<String>> attributeKeysByNormalizedKey;

    public BaggageSpanProcessor(Set<String> allowlistKeys, List<String> denyPatternStrings) {
        this.allowlist = allowlistKeys.stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        this.denyPatterns = denyPatternStrings.stream()
                .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
                .toList();

        this.attributeKeysByNormalizedKey = new ConcurrentHashMap<>();
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
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
                attributes.put(CORRELATION_ATTRIBUTE_KEY, value);
            }
            return;
        }

        attributes.put(attributeKeyFor(key, normalizedKey), value);
    }

    private AttributeKey<String> attributeKeyFor(String key, String normalizedKey) {
        return attributeKeysByNormalizedKey.computeIfAbsent(
                normalizedKey, ignored -> AttributeKey.stringKey(ATTRIBUTE_PREFIX + key)
        );
    }

    private static boolean isCanonicalCorrelationId(String value) {
        if (value.length() > CORRELATION_ID_MAX_LENGTH) {
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
}
