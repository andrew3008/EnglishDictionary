package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.builder.DatabaseSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.HttpTracing;
import space.br1440.platform.tracing.api.span.builder.KafkaTracing;
import space.br1440.platform.tracing.api.span.builder.RpcTracing;
import space.br1440.platform.tracing.api.span.builder.TransportTracing;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Objects;

public final class DefaultTransportTracing implements TransportTracing {

    private final TracingRuntime implementation;
    private final AttributePolicy policy;

    public DefaultTransportTracing(@Nonnull TracingRuntime implementation,
                                   @Nonnull AttributePolicy policy) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    @Nonnull
    public HttpTracing http() {
        return new DefaultHttpTracing(implementation, policy);
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder database() {
        return new DatabaseSpanBuilderImpl(implementation, policy);
    }

    @Override
    @Nonnull
    public RpcTracing rpc() {
        return new DefaultRpcTracing(implementation, policy);
    }

    @Override
    @Nonnull
    public KafkaTracing kafka() {
        return new DefaultKafkaTracing(implementation, policy);
    }
}
