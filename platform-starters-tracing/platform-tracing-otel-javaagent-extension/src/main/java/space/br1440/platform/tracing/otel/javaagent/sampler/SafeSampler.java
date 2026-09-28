package space.br1440.platform.tracing.otel.javaagent.sampler;

import java.util.List;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SamplingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.resilience.CircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.resilience.DegradedModeController;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import lombok.NonNull;

public final class SafeSampler implements Sampler, PlatformManagedSampler {

    private final Sampler delegate;
    private final TracingDiagnostics diagnostics;
    private final DegradedModeController degradedController;
    private final RateLimitedLogger log = new RateLimitedLogger(SafeSampler.class);

    SafeSampler(Sampler delegate) {
        this(delegate, TracingDiagnostics.shared());
    }

    SafeSampler(Sampler delegate, TracingDiagnostics diagnostics) {
        this(delegate, diagnostics, new DegradedModeController(diagnostics));
    }

    SafeSampler(Sampler delegate, TracingDiagnostics diagnostics, DegradedModeController degradedController) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.degradedController = Objects.requireNonNull(degradedController, "degradedController");
    }

    @Override
    public SamplingResult shouldSample(@NonNull Context parentContext, @NonNull String traceId, @NonNull String name,
                                       @NonNull SpanKind spanKind, @NonNull Attributes attributes,
                                       @NonNull List<LinkData> parentLinks) {
        CircuitBreaker.State execState = degradedController.acquireExecutionState(DegradedModeController.Component.SAMPLER);
        if (execState != CircuitBreaker.State.OPEN) {
            try {
                SamplingResult result = delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
                if (result != null) {
                    degradedController.recordSuccess(DegradedModeController.Component.SAMPLER);
                    return result;
                }

                degradedController.recordFailure(DegradedModeController.Component.SAMPLER);
                diagnostics.recordSamplerFailure();
                log.log(SamplingLogEvent.SAMPLER_FAILED_CLOSED_TO_DROP, fields ->
                        fields.putString(AgentLogField.REASON, "RETURNED_NULL"));
            } catch (Throwable ex) {
                PlatformThrowables.propagateIfFatal(ex);
                degradedController.recordFailure(DegradedModeController.Component.SAMPLER);
                diagnostics.recordSamplerFailure();
                log.log(SamplingLogEvent.SAMPLER_FAILED_CLOSED_TO_DROP, fields -> fields
                        .putString(AgentLogField.REASON, "THREW")
                        .cause(ex));
            }
        }

        return SamplingResult.create(SamplingDecision.DROP);
    }

    @Override
    public String getDescription() {
        try {
            return "SafeSampler{" + delegate.getDescription() + "}";
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            return "SafeSampler{description_unavailable}";
        }
    }

    @Override
    public String toString() {
        return getDescription();
    }

    @Override
    public CompositeSampler platformCompositeSampler() {
        if (delegate instanceof PlatformManagedSampler managed) {
            return managed.platformCompositeSampler();
        }

        return null;
    }
}
