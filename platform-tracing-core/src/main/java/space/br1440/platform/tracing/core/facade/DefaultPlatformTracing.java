package space.br1440.platform.tracing.core.facade;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.ActiveTraceContextView;
import space.br1440.platform.tracing.core.manual.DefaultManualTracing;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;

import java.util.Objects;

public class DefaultPlatformTracing implements PlatformTracing {

    private record RuntimeHolder(@Nonnull TracingRuntime runtime,
                                 @Nonnull ManualTracing manual) {}

    private volatile RuntimeHolder active;
    private final RuntimeHolder original;

    public DefaultPlatformTracing(@Nonnull TracingRuntime implementation) {
        Objects.requireNonNull(implementation, "implementation");

        var holder = buildHolder(implementation);
        this.original = holder;
        this.active = holder;
    }

    private static RuntimeHolder buildHolder(@Nonnull TracingRuntime runtime) {
        return new RuntimeHolder(runtime, new DefaultManualTracing(runtime, runtime.attributePolicy()));
    }

    @Override
    @Nonnull
    public ActiveTraceContextView traceContext() {
        return active.runtime().currentTraceContext();
    }

    @Override
    @Nonnull
    public ManualTracing manual() {
        return active.manual();
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
