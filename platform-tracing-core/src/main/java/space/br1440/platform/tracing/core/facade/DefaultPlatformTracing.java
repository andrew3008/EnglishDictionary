package space.br1440.platform.tracing.core.facade;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.core.runtime.DelegatingTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;
import space.br1440.platform.tracing.core.manual.DefaultManualTracing;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import io.opentelemetry.api.OpenTelemetry;

public class DefaultPlatformTracing implements PlatformTracing {

    private final TracingRuntime implementation;
    private final ManualTracing manualTracing;

    public DefaultPlatformTracing(@Nonnull TracingRuntime implementation) {
        this.implementation = implementation;
        this.manualTracing = new DefaultManualTracing(implementation, attributePolicyFor(implementation));
    }

    private static AttributePolicy attributePolicyFor(TracingRuntime implementation) {
        TracingRuntime core = unwrapDelegate(implementation);
        if (core instanceof OtelTracingRuntime defaultImpl) {
            return defaultImpl.attributePolicy();
        }

        return new AttributePolicy();
    }

    private static TracingRuntime unwrapDelegate(TracingRuntime implementation) {
        if (implementation instanceof DelegatingTracingRuntime delegating) {
            return unwrapDelegate(delegating.delegate());
        }

        return implementation;
    }

    public DefaultPlatformTracing(@Nonnull OpenTelemetry openTelemetry) {
        this(openTelemetry, new AttributePolicy());
    }

    public DefaultPlatformTracing(@Nonnull OpenTelemetry openTelemetry,
                                  @Nonnull AttributePolicy policy) {
        this(openTelemetry, policy, ExceptionRecorder.secureDefault());
    }

    public DefaultPlatformTracing(@Nonnull OpenTelemetry openTelemetry,
                                  @Nonnull AttributePolicy policy,
                                  @Nonnull ExceptionRecorder exceptionRecorder) {
        this(new OtelTracingRuntime(openTelemetry, policy, exceptionRecorder));
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

    public boolean isFacadeEnabled() {
        return implementation.state().mode() == TracingMode.ENABLED;
    }

    public void setFacadeEnabled(boolean enabled) {
        TracingRuntime core = unwrapDelegate(implementation);
        if (core instanceof OtelTracingRuntime defaultImpl) {
            defaultImpl.setKillSwitchEnabled(enabled);
        }
    }

    @Nonnull
    public TracingRuntime tracingImplementation() {
        return implementation;
    }
}
