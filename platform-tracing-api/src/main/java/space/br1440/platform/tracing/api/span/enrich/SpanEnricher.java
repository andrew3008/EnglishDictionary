package space.br1440.platform.tracing.api.span.enrich;

import jakarta.annotation.Nonnull;

import java.util.function.Consumer;

/**
 * Обогащает текущий записываемый span разрешёнными платформенными атрибутами.
 * <p>
 * Если активного записываемого span нет, операция ничего не делает.
 */
@FunctionalInterface
public interface SpanEnricher {

    /**
     * Передаёт callback безопасную область обогащения текущего span.
     *
     * @param enrichment callback, задающий разрешённые атрибуты
     */
    void enrichCurrentSpan(@Nonnull Consumer<GenericSpanEnrichment> enrichment);
}
