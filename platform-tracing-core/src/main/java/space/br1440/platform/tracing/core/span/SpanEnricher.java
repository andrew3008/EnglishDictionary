package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.enrich.EnrichScope;
import space.br1440.platform.tracing.api.span.enrich.GenericEnrichScope;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Обогащение уже активного span'а платформенными атрибутами — главная потребность agent-first
 * модели (span создаёт OTel Java Agent, приложение лишь добавляет бизнес-контекст).
 * <p>
 * Два метода с разной строгостью governance:
 * <ul>
 *   <li>{@link #enrichCurrentSpan} — основной путь, ТОЛЬКО generic / platform-safe атрибуты
 *       (typed-методы {@link GenericEnrichScope}, без произвольного {@code AttributeKey});</li>
 *   <li>{@link #enrichCurrentSpanIfPlatformCategory} — category-specific, но применяется ТОЛЬКО
 *       если категория подтверждена платформенным маркером (для чистых Агентских span'ов — no-op).</li>
 * </ul>
 * Атрибуты/kind активного span'а НЕ читаются (spec запрещает доступ к атрибутам чужого span'а);
 * категория сверяется через internal {@code ContextKey}, который платформа сама проставляет при
 * создании span'а. Перед обогащением проверяется {@code isRecording()} — дорогие операции не
 * выполняются для drop-sampled (Noop) span'ов.
 */
public final class SpanEnricher {

    private final AttributePolicy policy;

    public SpanEnricher(@Nonnull AttributePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Основной agent-first путь: обогащает активный span generic / platform-safe атрибутами.
     * No-op, если активного валидного span'а нет или span не пишется.
     */
    public void enrichCurrentSpan(@Nonnull Consumer<GenericEnrichScope> fn) {
        Objects.requireNonNull(fn, "fn");
        Span span = Span.current();
        if (span.getSpanContext().isValid() && span.isRecording()) {
            fn.accept(new DefaultGenericEnrichScope(span));
        }
    }

    /**
     * Marker-based category-specific обогащение: применяет {@code fn} ТОЛЬКО если активный span
     * создан платформой с категорией {@code expected} (маркер {@code PLATFORM_SPAN_CATEGORY}).
     * Для чистых Агентских span'ов (без маркера) — безопасный no-op.
     */
    public void enrichCurrentSpanIfPlatformCategory(@Nonnull SpanCategory expected,
                                                    @Nonnull Consumer<EnrichScope> fn) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(fn, "fn");
        SpanCategory marker = Context.current().get(PlatformSpanContextKeys.PLATFORM_SPAN_CATEGORY);
        Span span = Span.current();
        if (marker == expected && span.getSpanContext().isValid() && span.isRecording()) {
            fn.accept(new DefaultEnrichScope(span, policy, expected));
        }
    }
}
