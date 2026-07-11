package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

final class ImmutableSpanTopologySpec implements SpanTopologySpec {

    private final Topology topology;
    private final List<SpanLinkContext> links;

    private ImmutableSpanTopologySpec(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        this.topology = topology;
        this.links = links;
    }

    static SpanTopologySpec child() {
        return new ImmutableSpanTopologySpec(Topology.CHILD, List.of());
    }

    static SpanTopologySpec root() {
        return new ImmutableSpanTopologySpec(Topology.ROOT, List.of());
    }

    static SpanTopologySpec detached() {
        return new ImmutableSpanTopologySpec(Topology.DETACHED, List.of());
    }

    static SpanTopologySpec of(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        return new ImmutableSpanTopologySpec(topology, List.copyOf(links));
    }

    @Override
    @Nonnull
    public Topology topology() {
        return topology;
    }

    @Override
    @Nonnull
    public List<SpanLinkContext> links() {
        return links;
    }

    static void validateTopologyLinks(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        if (links.isEmpty()) {
            return;
        }

        if (topology == Topology.DETACHED) {
            throw new IllegalStateException("DETACHED topology forbids pre-start links");
        }

        if (topology == Topology.CHILD) {
            throw new IllegalStateException("CHILD topology forbids pre-start links");
        }
    }
}
