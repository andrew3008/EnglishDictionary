package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.enrich.EnrichScope;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Реализация {@link EnrichScope} для marker-based обогащения: допускает типизированные
 * {@code AttributeKey}, но пишет ТОЛЬКО ключи из allowlist контракта категории. Ключи вне
 * allowlist отбрасываются с WARN (once per key+category) — записать мимо {@code CategoryContract}
 * нельзя, governance сохраняется.
 */
final class DefaultEnrichScope implements EnrichScope {

    private static final Logger log = LoggerFactory.getLogger(DefaultEnrichScope.class);

    private static final Set<String> WARNED_ONCE = ConcurrentHashMap.newKeySet();

    private final Span span;
    private final AttributePolicy policy;
    private final SpanCategory category;

    DefaultEnrichScope(@Nonnull Span span, @Nonnull AttributePolicy policy, @Nonnull SpanCategory category) {
        this.span = span;
        this.policy = policy;
        this.category = category;
    }

    @Override
    @Nonnull
    public <V> EnrichScope attribute(@Nonnull AttributeKey<V> key, @Nonnull V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (policy.contractFor(category).allowlist().contains(key)) {
            span.setAttribute(key, value);
        } else {
            warnOnce(key);
        }
        return this;
    }

    @Override
    @Nonnull
    public EnrichScope result(@Nonnull SpanResult result) {
        span.setAttribute(SemconvKeys.PLATFORM_RESULT, Objects.requireNonNull(result, "result").value());
        return this;
    }

    private void warnOnce(AttributeKey<?> key) {
        if (WARNED_ONCE.add(category.name() + '|' + key.getKey())) {
            log.warn("enrich: атрибут '{}' вне allowlist категории {} — отброшен (governance)",
                    key.getKey(), category);
        }
    }
}
