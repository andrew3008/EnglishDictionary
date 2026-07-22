package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

/**
 * Обёртка над {@link SamplingResult} delegate, добавляющая дополнительные sampling-атрибуты.
 * <p>
 * Используется только для delegate paths с {@link SamplingDecision#RECORD_AND_SAMPLE} или
 * {@link SamplingDecision#RECORD_ONLY} ({@code NOT_RECORD}): attrs попадают на span at sampling time
 * (OTel spec #2198).
 * <p>
 * {@link SamplingDecision#DROP} path не оборачивается — span не создаётся, attrs теряются.
 * <p>
 * При {@code NOT_RECORD} span создаётся, attrs (в т.ч. {@code platform.sampling.reason}) сохраняются,
 * но span не экспортируется.
 */
record ForwardingSamplingResult(SamplingResult delegate, Attributes extra, Attributes merged)
        implements SamplingResult {

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
    public TraceState getUpdatedTraceState(TraceState parentTraceState) {
        return delegate.getUpdatedTraceState(parentTraceState);
    }

    private static Attributes mergeAttributes(Attributes delegateAttributes, Attributes extra) {
        return Attributes.builder()
                .putAll(delegateAttributes)
                .putAll(extra)
                .build();
    }
}
