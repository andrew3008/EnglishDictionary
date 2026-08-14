package space.br1440.platform.tracing.api.span.enrich;

import jakarta.annotation.Nonnull;

import java.util.function.Consumer;

/**
 * Обогащает текущий записываемый span разрешёнными платформенными атрибутами.
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
