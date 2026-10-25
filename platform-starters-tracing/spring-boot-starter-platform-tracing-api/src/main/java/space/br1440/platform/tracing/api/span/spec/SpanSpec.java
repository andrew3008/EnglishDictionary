package space.br1440.platform.tracing.api.span.spec;

import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;

/**
 * Спецификация span'а для {@code spans().fromSpec(spec)}.
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
    Map<String, SpanSpecAttributeValue> attributes();

    @Nonnull
    Optional<SpanSpecReason> reason();

    @Nonnull
    Optional<String> reference();

}
