package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.Nonnull;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.PlatformBaggageKeys;
import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageKeyPolicy;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

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
 * Allowlist и deny-паттерны фиксируются на старте — runtime mutation
 * отсутствует. Ключ {@code platform.correlation.id} обрабатывается отдельно: маппится на
 * {@link PlatformAttributes#PLATFORM_CORRELATION_ID} только если значение проходит canonical-id
 * валидацию ({@link #isCanonicalCorrelationId}), защищая от записи произвольных строк под
 * зарезервированный platform-атрибут.
 */
public final class BaggageSpanProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

    private static final String DIAGNOSTIC_ID = JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_BAGGAGE;
    private static final String ATTRIBUTE_PREFIX = "baggage.";
    private static final int CORRELATION_ID_MAX_LENGTH = 128;
    private static final AttributeKey<String> CORRELATION_ATTRIBUTE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID);

    private final BaggageKeyPolicy policy;

    private final Map<String, AttributeKey<String>> attributeKeysByNormalizedKey;

    public BaggageSpanProcessor(BaggageKeyPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.attributeKeysByNormalizedKey = new ConcurrentHashMap<>();
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        if (policy.isEmpty()) {
            return;
        }

        AttributesBuilder attributes = Attributes.builder();
        Baggage.fromContext(parentContext)
                .forEach((key, entry) -> copyIfAllowed(key, entry, attributes));
        span.setAllAttributes(attributes.build());
    }

    private void copyIfAllowed(String key, BaggageEntry entry, AttributesBuilder attributes) {
        if (entry == null || !policy.allows(key)) {
            return;
        }

        String normalizedKey = key.toLowerCase(Locale.ROOT);

        String value = entry.getValue();
        if (StringUtils.isNullOrBlank(value)) {
            return;
        }

        if (PlatformBaggageKeys.CORRELATION_ID.equals(normalizedKey)) {
            if (isCanonicalCorrelationId(value)) {
                attributes.put(CORRELATION_ATTRIBUTE_KEY, value);
            }
            return;
        }

        attributes.put(attributeKeyFor(normalizedKey), value);
    }

    private AttributeKey<String> attributeKeyFor(String normalizedKey) {
        return attributeKeysByNormalizedKey.computeIfAbsent(
                normalizedKey, key -> AttributeKey.stringKey(ATTRIBUTE_PREFIX + key)
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
