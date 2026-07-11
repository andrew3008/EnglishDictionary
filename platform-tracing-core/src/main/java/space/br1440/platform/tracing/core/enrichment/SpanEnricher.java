package space.br1440.platform.tracing.core.enrichment;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.enrich.SpanEnrichment;
import space.br1440.platform.tracing.api.span.enrich.GenericSpanEnrichment;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.runtime.otel.context.PlatformSpanContextKeys;

import java.util.Objects;
import java.util.function.Consumer;

public final class SpanEnricher {

    private final AttributePolicy policy;

    public SpanEnricher(@Nonnull AttributePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public void enrichCurrentSpan(@Nonnull Consumer<GenericSpanEnrichment> fn) {
        Objects.requireNonNull(fn, "fn");

        Span span = Span.current();
        if (span.getSpanContext().isValid() && span.isRecording()) {
            fn.accept(new DefaultGenericSpanEnrichment(span));
        }
    }

    public void enrichCurrentSpanIfPlatformCategory(@Nonnull SpanCategory expected,
                                                    @Nonnull Consumer<SpanEnrichment> fn) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(fn, "fn");

        SpanCategory marker = Context.current().get(PlatformSpanContextKeys.PLATFORM_SPAN_CATEGORY);
        Span span = Span.current();
        if (marker == expected && span.getSpanContext().isValid() && span.isRecording()) {
            fn.accept(new DefaultSpanEnrichment(span, policy, expected));
        }
    }
}
