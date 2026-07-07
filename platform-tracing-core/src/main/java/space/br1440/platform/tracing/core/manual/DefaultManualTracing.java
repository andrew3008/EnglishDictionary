package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.OperationSpanBuilder;
import space.br1440.platform.tracing.api.manual.TransportTracing;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpecifiedSpan;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;

public final class DefaultManualTracing implements ManualTracing {

    private final TracingImplementation implementation;
    private final AttributePolicy policy;

    public DefaultManualTracing(@Nonnull TracingImplementation implementation,
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
    public SpecifiedSpan spanFromSpec(@Nonnull SpanSpec spec) {
        return new SpecifiedSpanImpl(implementation, spec);
    }
}
