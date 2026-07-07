package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

final class ImmutableSpanOptions implements SpanOptions {

    private final Topology topology;
    private final List<SpanLinkContext> links;

    private ImmutableSpanOptions(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        this.topology = topology;
        this.links = links;
    }

    static SpanOptions child() {
        return new ImmutableSpanOptions(Topology.CHILD, List.of());
    }

    static SpanOptions root() {
        return new ImmutableSpanOptions(Topology.ROOT, List.of());
    }

    static SpanOptions detached() {
        return new ImmutableSpanOptions(Topology.DETACHED, List.of());
    }

    static SpanOptions of(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        return new ImmutableSpanOptions(topology, List.copyOf(links));
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
            throw new IllegalStateException("CHILD topology forbids pre-start links in v1");
        }
    }
}
