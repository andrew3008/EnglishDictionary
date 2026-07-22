package space.br1440.platform.tracing.otel.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.api.span.builder.DatabaseSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.HttpTracing;
import space.br1440.platform.tracing.api.span.builder.KafkaTracing;
import space.br1440.platform.tracing.api.span.builder.RpcTracing;
import space.br1440.platform.tracing.api.span.builder.TransportTracing;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

import java.util.Objects;

public final class DefaultTransportTracing implements TransportTracing {

    private final TracingRuntime implementation;
    private final AttributePolicy policy;
    private final OtelTraceparentReader traceparentReader;

    public DefaultTransportTracing(@Nonnull TracingRuntime implementation,
                                   @Nonnull AttributePolicy policy) {
        this(implementation, policy, DefaultSpanFactory.readerFor(implementation));
    }

    DefaultTransportTracing(@Nonnull TracingRuntime implementation,
                            @Nonnull AttributePolicy policy,
                            @Nonnull OtelTraceparentReader traceparentReader) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
    }

    @Override
    @Nonnull
    public HttpTracing http() {
        return new DefaultHttpTracing(implementation, policy, traceparentReader);
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder database() {
        return new DatabaseSpanBuilderImpl(implementation, policy, traceparentReader);
    }

    @Override
    @Nonnull
    public RpcTracing rpc() {
        return new DefaultRpcTracing(implementation, policy, traceparentReader);
    }

    @Override
    @Nonnull
    public KafkaTracing kafka() {
        return new DefaultKafkaTracing(implementation, policy, traceparentReader);
    }
}
