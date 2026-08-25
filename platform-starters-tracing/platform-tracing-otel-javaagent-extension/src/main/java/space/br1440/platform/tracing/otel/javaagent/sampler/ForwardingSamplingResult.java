package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

import jakarta.annotation.Nonnull;

record ForwardingSamplingResult(SamplingResult delegate, Attributes extra, Attributes merged) implements SamplingResult {

    ForwardingSamplingResult(SamplingResult delegate, Attributes extra) {
        this(delegate, extra, mergeAttributes(delegate.getAttributes(), extra));
    }

    @Override
    public SamplingDecision getDecision() {
        return delegate.getDecision();
    }

    @Override
    public Attributes getAttributes() {
        return merged;
    }

    @Override
    public TraceState getUpdatedTraceState(@Nonnull TraceState parentTraceState) {
        return delegate.getUpdatedTraceState(parentTraceState);
    }

    private static Attributes mergeAttributes(Attributes delegateAttributes, Attributes extra) {
        return Attributes.builder()
                .putAll(delegateAttributes)
                .putAll(extra)
                .build();
    }
}
