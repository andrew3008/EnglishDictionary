package space.br1440.platform.tracing.api.span.enrich;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanResult;

/**
 * Контракт обогащения span'а <b>только generic / platform-safe</b> атрибутами.
 * <p>
 * Не предполагает доступа к атрибутам или kind уже запущенного span'а —
 * только добавление разрешённых атрибутов. Это позволяет безопасно обогащать
 * span'ы, созданные OTel Java Agent'ом, не нарушая спецификацию.
 */
public interface GenericSpanEnrichment {

    /** {@code platform.request_id} — технический request id (high-cardinality, не для метрик). */
    @Nonnull
    GenericSpanEnrichment requestId(@Nonnull String requestId);

    /** {@code platform.user_hash} — псевдонимизированный идентификатор пользователя (не raw id/PII). */
    @Nonnull
    GenericSpanEnrichment userHash(@Nonnull String hash);

    /** {@code platform.trace.result} — финальный статус операции. */
    @Nonnull
    GenericSpanEnrichment result(@Nonnull SpanResult result);

}
