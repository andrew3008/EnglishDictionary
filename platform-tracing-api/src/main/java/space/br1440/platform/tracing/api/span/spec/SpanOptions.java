package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

/**
 * Immutable public value model for span topology and pre-start links.
 * <p>
 * Not the preferred application-facing builder grammar; use {@link SpanSpecBuilder} convenience methods.
 */
public interface SpanOptions {

    @Nonnull
    Topology topology();

    @Nonnull
    List<SpanLinkContext> links();

    @Nonnull
    static SpanOptions child() {
        return ImmutableSpanOptions.child();
    }

    @Nonnull
    static SpanOptions root() {
        return ImmutableSpanOptions.root();
    }

    @Nonnull
    static SpanOptions detached() {
        return ImmutableSpanOptions.detached();
    }

    /**
     * Runtime/build-time guard for topology + pre-start link policy (Slice 1A / 5A).
     */
    static void validateTopologyLinks(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        ImmutableSpanOptions.validateTopologyLinks(topology, links);
    }
}
