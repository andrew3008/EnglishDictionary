package space.br1440.platform.tracing.otel.span.builder;

import java.util.Objects;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.builder.OperationSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.TransportTracing;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory;

/**
 * PA-0 spike: public bridge builder-factory.
 * Не хранит {@code TracingRuntime} и {@code AttributePolicy} — только spec-bridge и reader.
 */
public final class DefaultSpanBuilderFactory {

    private final DefaultSpanSpecFactory specFactory;
    private final OtelTraceparentReader traceparentReader;

    public DefaultSpanBuilderFactory(@Nonnull DefaultSpanSpecFactory specFactory,
                                     @Nonnull OtelTraceparentReader traceparentReader) {
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
    }

    @Nonnull
    public OperationSpanBuilder operation(@Nonnull String name) {
        Objects.requireNonNull(name, "name");
        return new ProposedOperationSpanBuilder(specFactory, traceparentReader, name);
    }

    @Nonnull
    public TransportTracing transport() {
        return new ProposedTransportTracing(specFactory, traceparentReader);
    }
}
