package space.br1440.platform.tracing.core.facade;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.core.manual.DefaultManualTracing;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;

import java.util.Objects;

/**
 * Default implementation of {@link PlatformTracing} (Slice 2).
 *
 * <h3>Kill-switch</h3>
 * Enabled/disabled state is managed by atomically swapping the active
 * {@link RuntimeHolder}. When disabled, the facade replaces its active runtime with a
 * {@link NoOpTracingRuntime}; re-enabling restores the original runtime. The swap is a
 * single {@code volatile} write, so {@link #traceContext()} and {@link #manual()} are
 * always consistent with each other (no split-brain between runtime and ManualTracing).
 *
 * <h3>OTel wiring</h3>
 * This class has no dependency on {@code io.opentelemetry}. OpenTelemetry-backed runtimes
 * are created via {@code OtelTracingRuntimeFactory} and injected through the
 * {@link #DefaultPlatformTracing(TracingRuntime)} constructor. Spring Boot autoconfigure
 * performs this wiring; see {@code TracingCoreAutoConfiguration}.
 */
public class DefaultPlatformTracing implements PlatformTracing {

    /**
     * Atomic unit of facade state: runtime + its ManualTracing companion.
     * A single {@code volatile} write ensures both are replaced together.
     */
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
        return new RuntimeHolder(runtime,
                new DefaultManualTracing(runtime, runtime.attributePolicy()));
    }

    @Override
    @Nonnull
    public TraceContextView traceContext() {
        return active.runtime().currentTraceContext();
    }

    @Override
    @Nonnull
    public ManualTracing manual() {
        return active.manual();
    }

    /**
     * Returns {@code true} when the facade is actively routing to the original runtime.
     */
    public boolean isFacadeEnabled() {
        return active.runtime().state().mode() == TracingMode.ENABLED;
    }

    /**
     * Enables or disables tracing through this facade.
     * <p>
     * Disabling replaces the active runtime with a {@link NoOpTracingRuntime}; enabling
     * restores the original runtime provided at construction time. The replacement is a
     * single atomic {@code volatile} write: callers observing {@link #traceContext()} or
     * {@link #manual()} after this call will see a consistent pair.
     * <p>
     * <b>Thread-safety:</b> concurrent enable/disable calls are safe; the last writer wins.
     * Spans already in-flight on the previous runtime complete normally.
     */
    public void setFacadeEnabled(boolean enabled) {
        this.active = enabled
                ? original
                : buildHolder(NoOpTracingRuntime.disabledByConfiguration("setFacadeEnabled(false)"));
    }

    /**
     * Exposes the currently active {@link TracingRuntime} for diagnostics and testing.
     * Not part of the public {@link PlatformTracing} API.
     */
    @Nonnull
    public TracingRuntime tracingRuntime() {
        return active.runtime();
    }
}
