package space.br1440.platform.tracing.core;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.impl.DefaultTracingImplementation;
import space.br1440.platform.tracing.core.impl.DelegatingTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingMode;
import space.br1440.platform.tracing.core.manual.DefaultManualTracing;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import io.opentelemetry.api.OpenTelemetry;

public class DefaultPlatformTracing implements PlatformTracing {

    private final TracingImplementation implementation;
    private final ManualTracing manualTracing;

    public DefaultPlatformTracing(@Nonnull TracingImplementation implementation) {
        this.implementation = implementation;
        this.manualTracing = new DefaultManualTracing(implementation, attributePolicyFor(implementation));
    }

    private static AttributePolicy attributePolicyFor(TracingImplementation implementation) {
        TracingImplementation core = unwrapDelegate(implementation);
        if (core instanceof DefaultTracingImplementation defaultImpl) {
            return defaultImpl.attributePolicy();
        }

        return new AttributePolicy();
    }

    private static TracingImplementation unwrapDelegate(TracingImplementation implementation) {
        if (implementation instanceof DelegatingTracingImplementation delegating) {
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
        this(new DefaultTracingImplementation(openTelemetry, policy, exceptionRecorder));
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
        TracingImplementation core = unwrapDelegate(implementation);
        if (core instanceof DefaultTracingImplementation defaultImpl) {
            defaultImpl.setKillSwitchEnabled(enabled);
        }
    }

    @Nonnull
    public TracingImplementation tracingImplementation() {
        return implementation;
    }
}
