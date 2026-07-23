package space.br1440.platform.tracing.otel.span.builder;

import java.util.Objects;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.builder.DatabaseSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.HttpTracing;
import space.br1440.platform.tracing.api.span.builder.KafkaTracing;
import space.br1440.platform.tracing.api.span.builder.RpcTracing;
import space.br1440.platform.tracing.api.span.builder.TransportTracing;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory;

/**
 * PA-0 spike: transport sub-factory без runtime/policy на operational surface.
 */
final class ProposedTransportTracing implements TransportTracing {

    private final DefaultSpanSpecFactory specFactory;
    private final OtelTraceparentReader traceparentReader;

    ProposedTransportTracing(@Nonnull DefaultSpanSpecFactory specFactory,
                             @Nonnull OtelTraceparentReader traceparentReader) {
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
    }

    @Override
    @Nonnull
    public HttpTracing http() {
        throw new UnsupportedOperationException("PA-0 spike stub");
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder database() {
        throw new UnsupportedOperationException("PA-0 spike stub");
    }

    @Override
    @Nonnull
    public RpcTracing rpc() {
        throw new UnsupportedOperationException("PA-0 spike stub");
    }

    @Override
    @Nonnull
    public KafkaTracing kafka() {
        throw new UnsupportedOperationException("PA-0 spike stub");
    }
}
