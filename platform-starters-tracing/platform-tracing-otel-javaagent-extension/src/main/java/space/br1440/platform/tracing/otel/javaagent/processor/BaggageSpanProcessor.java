package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.PlatformBaggageKeys;
import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageKeyPolicy;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.runtime.propagation.CorrelationIdSupport;

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
 * Платформенная проекция корреляции и копирование пользовательских baggage-ключей по allowlist.
 * <p>
 * Проекция {@code platform.correlation.id} в {@link PlatformAttributes#PLATFORM_CORRELATION_ID}
 * безусловна и не зависит от allowlist. Прочие ключи {@code platform.*} отбрасываются.
 * Allowlist и deny-паттерны фиксируются на старте, runtime mutation отсутствует.
 */
public final class BaggageSpanProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

    private static final String DIAGNOSTIC_ID = JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_BAGGAGE;
    private static final String ATTRIBUTE_PREFIX = "baggage.";
    private static final AttributeKey<String> CORRELATION_ATTRIBUTE_KEY =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID);

    private final BaggageKeyPolicy policy;
    private final Map<String, AttributeKey<String>> attributeKeysByKey;

    public BaggageSpanProcessor(BaggageKeyPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.attributeKeysByKey = new ConcurrentHashMap<>();
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        Baggage baggage = Baggage.fromContext(parentContext);
        if (baggage.isEmpty()) {
            return;
        }

        if (policy.isEmpty()) {
            String correlation = canonicalOrNull(baggage.getEntryValue(PlatformBaggageKeys.CORRELATION_ID));
            if (correlation != null) {
                span.setAttribute(CORRELATION_ATTRIBUTE_KEY, correlation);
            }
            return;
        }

        AttributesBuilder attributes = Attributes.builder();
        baggage.forEach((key, entry) -> copyIfAllowed(key, entry, attributes));
        Attributes built = attributes.build();
        if (!built.isEmpty()) {
            span.setAllAttributes(built);
        }
    }

    @Nullable
    private static String canonicalOrNull(@Nullable String value) {
        return CorrelationIdSupport.isCanonical(value) ? value : null;
    }

    private void copyIfAllowed(String key, BaggageEntry entry, AttributesBuilder attributes) {
        if (PlatformBaggageKeys.CORRELATION_ID.equals(key)) {
            String correlation = canonicalOrNull(entry.getValue());
            if (correlation != null) {
                attributes.put(CORRELATION_ATTRIBUTE_KEY, correlation);
            }
            return;
        }

        if (PlatformBaggageKeys.isReserved(key) || !policy.allows(key)) {
            return;
        }

        String value = entry.getValue();
        if (StringUtils.isNullOrBlank(value)) {
            return;
        }
        attributes.put(attributeKeyFor(key), value);
    }

    private AttributeKey<String> attributeKeyFor(String key) {
        return attributeKeysByKey.computeIfAbsent(key, name -> AttributeKey.stringKey(ATTRIBUTE_PREFIX + name));
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
