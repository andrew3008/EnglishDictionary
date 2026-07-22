package space.br1440.platform.tracing.core.facade;

import java.util.Objects;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.builder.ActiveTraceContextView;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.core.manual.DefaultSpanFactory;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

public final class NoopTraceOperations implements TraceOperations {

    public static final NoopTraceOperations INSTANCE = new NoopTraceOperations(NoOpTracingRuntime.noop());

    private final TracingRuntime implementation;
    private final SpanFactory spanFactory;

    private NoopTraceOperations(@Nonnull TracingRuntime implementation) {
        this.implementation = implementation;
        this.spanFactory = new DefaultSpanFactory(implementation, new AttributePolicy());
    }

    public static NoopTraceOperations backedBy(@Nonnull TracingRuntime implementation) {
        return new NoopTraceOperations(implementation);
    }

    @Override
    @Nonnull
    public ActiveTraceContextView traceContext() {
        return implementation.currentTraceContext();
    }

    @Override
    @Nonnull
    public SpanFactory spans() {
        return spanFactory;
    }

    @Override
    @Nonnull
    public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
        return implementation.openCorrelationScope(correlationId);
    }

    @Override
    public void withCorrelationId(@Nonnull String correlationId, @Nonnull Runnable action) {
        Objects.requireNonNull(action, "action");
        try (CorrelationScope ignored = openCorrelationScope(correlationId)) {
            action.run();
        }
    }

    @Override
    public <T> T withCorrelationId(@Nonnull String correlationId,
                                   @Nonnull ThrowingSupplier<T> action) throws Exception {
        Objects.requireNonNull(action, "action");
        try (CorrelationScope ignored = openCorrelationScope(correlationId)) {
            return action.get();
        }
    }
}
