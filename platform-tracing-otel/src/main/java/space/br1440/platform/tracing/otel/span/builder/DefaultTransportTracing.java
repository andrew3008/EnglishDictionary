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

final class DefaultTransportTracing implements TransportTracing {

    private final DefaultSpanSpecFactory specFactory;
    private final OtelTraceparentReader traceparentReader;

    DefaultTransportTracing(@Nonnull DefaultSpanSpecFactory specFactory,
                            @Nonnull OtelTraceparentReader traceparentReader) {
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
    }

    @Override
    @Nonnull
    public HttpTracing http() {
        return new DefaultHttpTracing(specFactory, traceparentReader);
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder database() {
        return new DatabaseSpanBuilderImpl(specFactory, traceparentReader);
    }

    @Override
    @Nonnull
    public RpcTracing rpc() {
        return new DefaultRpcTracing(specFactory, traceparentReader);
    }

    @Override
    @Nonnull
    public KafkaTracing kafka() {
        return new DefaultKafkaTracing(specFactory, traceparentReader);
    }
}
