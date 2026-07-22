package space.br1440.platform.tracing.otel.enrichment;

import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.enrich.GenericSpanEnrichment;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import java.util.Objects;

final class DefaultGenericSpanEnrichment implements GenericSpanEnrichment {

    private final Span span;

    DefaultGenericSpanEnrichment(@Nonnull Span span) {
        this.span = span;
    }

    @Override
    @Nonnull
    public GenericSpanEnrichment requestId(@Nonnull String requestId) {
        span.setAttribute(SemconvKeys.PLATFORM_REQUEST_ID, Objects.requireNonNull(requestId, "requestId"));
        return this;
    }

    @Override
    @Nonnull
    public GenericSpanEnrichment userHash(@Nonnull String hash) {
        span.setAttribute(SemconvKeys.PLATFORM_USER_HASH, Objects.requireNonNull(hash, "hash"));
        return this;
    }

    @Override
    @Nonnull
    public GenericSpanEnrichment result(@Nonnull SpanResult result) {
        span.setAttribute(SemconvKeys.PLATFORM_RESULT, Objects.requireNonNull(result, "result").value());
        return this;
    }
}
