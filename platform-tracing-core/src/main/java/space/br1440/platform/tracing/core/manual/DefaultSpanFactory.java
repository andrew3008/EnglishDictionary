package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.builder.OperationSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.TransportTracing;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Objects;

public final class DefaultSpanFactory implements SpanFactory {

    private final TracingRuntime implementation;
    private final AttributePolicy policy;

    public DefaultSpanFactory(@Nonnull TracingRuntime implementation,
                                @Nonnull AttributePolicy policy) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    @Nonnull
    public OperationSpanBuilder operation(@Nonnull String name) {
        Objects.requireNonNull(name, "name");
        return new OperationSpanBuilderImpl(implementation, policy, name);
    }

    @Override
    @Nonnull
    public TransportTracing transport() {
        return new DefaultTransportTracing(implementation, policy);
    }

    @Override
    @Nonnull
    public SpanExecution fromSpec(@Nonnull SpanSpec spec) {
        return new SpanExecutionImpl(implementation, spec);
    }
}
