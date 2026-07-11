package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

public interface SpanTopologySpec {

    @Nonnull
    Topology topology();

    @Nonnull
    List<SpanLinkContext> links();

    @Nonnull
    static SpanTopologySpec child() {
        return ImmutableSpanTopologySpec.child();
    }

    @Nonnull
    static SpanTopologySpec root() {
        return ImmutableSpanTopologySpec.root();
    }

    @Nonnull
    static SpanTopologySpec detached() {
        return ImmutableSpanTopologySpec.detached();
    }

    static void validateTopologyLinks(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        ImmutableSpanTopologySpec.validateTopologyLinks(topology, links);
    }
}
