package space.br1440.platform.tracing.core;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.core.impl.NoOpTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.manual.DefaultManualTracing;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

public final class NoOpPlatformTracing implements PlatformTracing {

    public static final NoOpPlatformTracing INSTANCE = new NoOpPlatformTracing(NoOpTracingImplementation.noop());

    private final TracingImplementation implementation;
    private final ManualTracing manualTracing;

    private NoOpPlatformTracing(@Nonnull TracingImplementation implementation) {
        this.implementation = implementation;
        this.manualTracing = new DefaultManualTracing(implementation, new AttributePolicy());
    }

    public static NoOpPlatformTracing backedBy(@Nonnull TracingImplementation implementation) {
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
    public TracingImplementation tracingImplementation() {
        return implementation;
    }
}
