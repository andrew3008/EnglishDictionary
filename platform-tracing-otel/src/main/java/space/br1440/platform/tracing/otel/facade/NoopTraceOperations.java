package space.br1440.platform.tracing.otel.facade;

import java.util.Objects;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

public final class NoopTraceOperations implements TraceOperations {

    public static final NoopTraceOperations INSTANCE = new NoopTraceOperations(NoOpTracingRuntime.noop());

    private final TracingRuntime runtime;
    private final SpanFactory spanFactory;

    private NoopTraceOperations(@Nonnull TracingRuntime runtime) {
        this.runtime = runtime;
        this.spanFactory = new DefaultSpanFactory(runtime, new AttributePolicy());
    }

    public static NoopTraceOperations backedBy(@Nonnull TracingRuntime runtime) {
        return new NoopTraceOperations(runtime);
    }

    @Override
    @Nonnull
    public ActiveTraceContextView traceContext() {
        return runtime.currentTraceContext();
    }

    @Override
    @Nonnull
    public SpanFactory spans() {
        return spanFactory;
    }

    @Override
    @Nonnull
    public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
        return runtime.openCorrelationScope(correlationId);
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
