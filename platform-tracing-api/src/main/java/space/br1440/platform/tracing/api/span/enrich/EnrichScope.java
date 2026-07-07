package space.br1440.platform.tracing.api.span.enrich;

import io.opentelemetry.api.common.AttributeKey;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanResult;

/**
 * Scope category-specific обогащения активного span'а — доступен ТОЛЬКО через marker-based
 * {@code SpanEnricher.enrichCurrentSpanIfPlatformCategory(category, fn)}, то есть когда категория
 * span'а подтверждена платформенным маркером ({@code PLATFORM_SPAN_CATEGORY}).
 * <p>
 * В отличие от {@link GenericEnrichScope}, допускает запись типизированных {@code AttributeKey},
 * но значения проходят через {@code AttributePolicy} категории: ключи вне allowlist категории
 * отбрасываются (WARN), запрещённые — не пишутся. Так governance сохраняется даже для
 * category-specific обогащения.
 */
public interface EnrichScope {

    /**
     * Записывает атрибут, если он разрешён контрактом категории. Ключи вне allowlist
     * отбрасываются (WARN) — мимо {@code CategoryContract} записать нельзя.
     */
    @Nonnull
    <V> EnrichScope attribute(@Nonnull AttributeKey<V> key, @Nonnull V value);

    /** {@code platform.trace.result} — финальный статус операции. */
    @Nonnull
    EnrichScope result(@Nonnull SpanResult result);

}
