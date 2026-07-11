package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.Map;
import java.util.Optional;

/**
 * Спецификация span'а для {@code manual().spanFromSpec(spec)}.
 */
public interface SpanSpec {

    @Nonnull
    static SpanSpecBuilder builder(@Nonnull String name) {
        return new DefaultSpanSpecBuilder(name);
    }

    @Nonnull
    String name();

    @Nonnull
    SpanCategory category();

    @Nonnull
    SpanRelationshipSpec relationship();

    @Nonnull
    Map<String, SpanAttributeValue> attributes();

    @Nonnull
    SpanSpecReason reason();

    @Nonnull
    Optional<String> reference();

}
