package space.br1440.platform.tracing.otel.span.enrich;

import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.enrich.GenericSpanEnrichment;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * OTel-backed реализация публичного контракта обогащения активного span.
 */
public final class DefaultSpanEnricher implements SpanEnricher {

    @Override
    public void enrichCurrentSpan(@Nonnull Consumer<GenericSpanEnrichment> enrichment) {
        Objects.requireNonNull(enrichment, "enrichment");

        Span span = Span.current();
        if (span.getSpanContext().isValid() && span.isRecording()) {
            enrichment.accept(new DefaultGenericSpanEnrichment(span));
        }
    }
}
