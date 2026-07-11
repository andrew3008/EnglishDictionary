package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

public interface SpanRelationshipSpec {

    /**
     * Relationship kind for parent/context selection.
     * <p>
     * This is not OpenTelemetry {@code SpanKind}; protocol/client/server kind is derived
     * separately from {@code SpanCategory}.
     */
    @Nonnull
    SpanRelationship kind();

    @Nonnull
    List<SpanLinkContext> links();

    @Nonnull
    static SpanRelationshipSpec child() {
        return ImmutableSpanRelationshipSpec.child();
    }

    @Nonnull
    static SpanRelationshipSpec root() {
        return ImmutableSpanRelationshipSpec.root();
    }

    @Nonnull
    static SpanRelationshipSpec detached() {
        return ImmutableSpanRelationshipSpec.detached();
    }

    static void validateRelationshipLinks(@Nonnull SpanRelationship relationship, @Nonnull List<SpanLinkContext> links) {
        ImmutableSpanRelationshipSpec.validateRelationshipLinks(relationship, links);
    }
}
