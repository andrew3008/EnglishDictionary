package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.List;

public interface SpanRelationshipSpec {

    @Nonnull
    SpanRelationship kind();

    @Nonnull
    List<RemoteSpanLink> links();

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

    static void validateRelationshipLinks(@Nonnull SpanRelationship relationship, @Nonnull List<RemoteSpanLink> links) {
        ImmutableSpanRelationshipSpec.validateRelationshipLinks(relationship, links);
    }

}
