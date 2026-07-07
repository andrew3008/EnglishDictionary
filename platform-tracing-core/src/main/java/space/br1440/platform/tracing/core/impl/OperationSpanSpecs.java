package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.Topology;

import java.util.List;
import java.util.Objects;

/**
 * Builds governed {@link SpanSpec} instances for {@code manual().operation(...)} routing.
 */
public final class OperationSpanSpecs {

    private OperationSpanSpecs() {
    }

    @Nonnull
    public static SpanSpec from(@Nonnull String name,
                         @Nonnull SpanCategory category,
                         @Nonnull Topology topology,
                         @Nonnull List<SpanLinkContext> links) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(links, "links");

        var builder = SpanSpec.builder(name)
                .category(category)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE);

        switch (topology) {
            case CHILD -> builder.child();
            case ROOT -> {
                builder.root();
                if (!links.isEmpty()) {
                    builder.linkedTo(links.toArray(SpanLinkContext[]::new));
                }
            }
            case DETACHED -> builder.detached();
        }
        return builder.build();
    }
}
