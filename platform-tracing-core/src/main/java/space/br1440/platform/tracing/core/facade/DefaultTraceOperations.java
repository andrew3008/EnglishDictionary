package space.br1440.platform.tracing.core.facade;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.manual.ActiveTraceContextView;
import space.br1440.platform.tracing.core.manual.DefaultSpanFactory;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;

import java.util.Objects;

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
        return active.runtime().currentTraceContext();
    }

    @Override
    @Nonnull
    public SpanFactory spans() {
        return active.spans();
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
