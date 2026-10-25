package space.br1440.platform.tracing.otel.facade;

import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

public class DefaultTraceOperations implements TraceOperations {

    private final TracingRuntime runtime;
    private final SpanFactory spans;

    public DefaultTraceOperations(@Nonnull TracingRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.spans = new DefaultSpanFactory(runtime, runtime.spanAttributeValidator());
    }

    @Override
    @Nonnull
    public ActiveTraceContextView traceContext() {
        return runtime.currentTraceContext();
    }

    @Override
    @Nonnull
    public SpanFactory spans() {
        return spans;
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

    @Nonnull
    public TracingRuntime tracingRuntime() {
        return runtime;
    }
}
