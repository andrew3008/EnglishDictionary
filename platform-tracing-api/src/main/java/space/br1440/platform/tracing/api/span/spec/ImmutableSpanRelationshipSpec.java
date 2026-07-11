package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

final class ImmutableSpanRelationshipSpec implements SpanRelationshipSpec {

    private final SpanRelationship relationship;
    private final List<SpanLinkContext> links;

    private ImmutableSpanRelationshipSpec(@Nonnull SpanRelationship relationship, @Nonnull List<SpanLinkContext> links) {
        this.relationship = relationship;
        this.links = links;
    }

    static SpanRelationshipSpec child() {
        return new ImmutableSpanRelationshipSpec(SpanRelationship.CHILD, List.of());
    }

    static SpanRelationshipSpec root() {
        return new ImmutableSpanRelationshipSpec(SpanRelationship.ROOT, List.of());
    }

    static SpanRelationshipSpec detached() {
        return new ImmutableSpanRelationshipSpec(SpanRelationship.DETACHED, List.of());
    }

    static SpanRelationshipSpec of(@Nonnull SpanRelationship relationship, @Nonnull List<SpanLinkContext> links) {
        return new ImmutableSpanRelationshipSpec(relationship, List.copyOf(links));
    }

    @Override
    @Nonnull
    public SpanRelationship kind() {
        return relationship;
    }

    @Override
    @Nonnull
    public List<SpanLinkContext> links() {
        return links;
    }

    static void validateRelationshipLinks(@Nonnull SpanRelationship relationship, @Nonnull List<SpanLinkContext> links) {
        if (links.isEmpty()) {
            return;
        }

        if (relationship == SpanRelationship.DETACHED) {
            throw new IllegalStateException("DETACHED relationship forbids pre-start links");
        }

        if (relationship == SpanRelationship.CHILD) {
            throw new IllegalStateException("CHILD relationship forbids pre-start links");
        }
    }
}
