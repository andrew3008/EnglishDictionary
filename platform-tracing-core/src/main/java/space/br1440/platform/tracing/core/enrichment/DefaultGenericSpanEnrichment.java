package space.br1440.platform.tracing.core.enrichment;

import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.enrich.GenericSpanEnrichment;

import java.util.Objects;

final class DefaultGenericSpanEnrichment implements GenericSpanEnrichment {

    private static final String BUSINESS_PREFIX = "platform.business.";

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

    @Override
    @Nonnull
    public GenericSpanEnrichment businessTag(@Nonnull String name, @Nonnull String value) {
        Objects.requireNonNull(value, "value");

        String normalized = normalize(name);
        if (!normalized.isEmpty()) {
            span.setAttribute(BUSINESS_PREFIX + normalized, value);
        }

        return this;
    }

    static String normalize(String name) {
        if (name == null) {
            return "";
        }

        String lower = name.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevUnderscore = false;
            } else if (!prevUnderscore && !sb.isEmpty()) {
                sb.append('_');
                prevUnderscore = true;
            }
        }

        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '_') {
            end--;
        }

        return sb.substring(0, end);
    }
}
