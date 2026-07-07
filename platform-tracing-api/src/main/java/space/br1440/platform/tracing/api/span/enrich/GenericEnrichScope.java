package space.br1440.platform.tracing.api.span.enrich;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanResult;

/**
 * Scope обогащения активного span'а <b>только generic / platform-safe</b> атрибутами.
 * <p>
 * Используется в основном agent-first пути {@code SpanEnricher.enrichCurrentSpan(...)}: обогащает
 * span, созданный OTel Java Agent'ом, не читая его атрибутов/kind (что запрещено spec).
 */
public interface GenericEnrichScope {

    /** {@code platform.request_id} — correlation id (high-cardinality, не для метрик). */
    @Nonnull
    GenericEnrichScope requestId(@Nonnull String requestId);

    /** {@code platform.user_hash} — псевдонимизированный идентификатор пользователя (не raw id/PII). */
    @Nonnull
    GenericEnrichScope userHash(@Nonnull String hash);

    /** {@code platform.trace.result} — финальный статус операции. */
    @Nonnull
    GenericEnrichScope result(@Nonnull SpanResult result);

    /**
     * Бизнес-тег. Полный ключ СТРОИТ сам scope: {@code platform.business.<normalizedName>}.
     * Пользователь передаёт только логическое имя (нормализуется) и значение — raw key
     * от пользователя не принимается, поэтому нельзя записать произвольный semconv-ключ.
     */
    @Nonnull
    GenericEnrichScope businessTag(@Nonnull String name, @Nonnull String value);

}
