package space.br1440.platform.tracing.core.facade;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.manual.DefaultManualTracing;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

public final class NoOpPlatformTracing implements PlatformTracing {

    public static final NoOpPlatformTracing INSTANCE = new NoOpPlatformTracing(NoOpTracingRuntime.noop());

    private final TracingRuntime implementation;
    private final ManualTracing manualTracing;

    private NoOpPlatformTracing(@Nonnull TracingRuntime implementation) {
        this.implementation = implementation;
        this.manualTracing = new DefaultManualTracing(implementation, new AttributePolicy());
    }

    public static NoOpPlatformTracing backedBy(@Nonnull TracingRuntime implementation) {
        return new NoOpPlatformTracing(implementation);
    }

    @Override
    @Nonnull
    public TraceContextView traceContext() {
        return implementation.currentTraceContext();
    }

    @Override
    @Nonnull
    public ManualTracing manual() {
        return manualTracing;
    }

    @Nonnull
    public TracingRuntime tracingImplementation() {
        return implementation;
    }
}
