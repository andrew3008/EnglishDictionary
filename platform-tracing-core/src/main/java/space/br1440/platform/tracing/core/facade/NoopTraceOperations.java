package space.br1440.platform.tracing.core.facade;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.builder.ActiveTraceContextView;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.manual.DefaultSpanFactory;
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
}
