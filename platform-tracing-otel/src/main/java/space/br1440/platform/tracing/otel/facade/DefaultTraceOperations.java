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
import space.br1440.platform.tracing.otel.runtime.state.TracingMode;

public class DefaultTraceOperations implements TraceOperations {

    private record RuntimeHolder(@Nonnull TracingRuntime runtime,
                                 @Nonnull SpanFactory spans) {}

    private volatile RuntimeHolder active;
    private final RuntimeHolder original;

    public DefaultTraceOperations(@Nonnull TracingRuntime implementation) {
        Objects.requireNonNull(implementation, "implementation");

        var holder = buildHolder(implementation);
        this.original = holder;
        this.active = holder;
    }

    private static RuntimeHolder buildHolder(@Nonnull TracingRuntime runtime) {
        return new RuntimeHolder(runtime, new DefaultSpanFactory(runtime, runtime.attributePolicy()));
    }

    @Override
    @Nonnull
    public ActiveTraceContextView traceContext() {
        return original.runtime().currentTraceContext();
    }

    @Override
    @Nonnull
    public SpanFactory spans() {
        return active.spans();
    }

    @Override
    @Nonnull
    public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
        return original.runtime().openCorrelationScope(correlationId);
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

    public boolean isFacadeEnabled() {
        return (active.runtime().state().mode() == TracingMode.ENABLED);
    }

    public void setFacadeEnabled(boolean enabled) {
        this.active = enabled
                ? original
                : buildHolder(NoOpTracingRuntime.disabledByConfiguration("setFacadeEnabled(false)"));
    }

    @Nonnull
    public TracingRuntime tracingRuntime() {
        return active.runtime();
    }
}
