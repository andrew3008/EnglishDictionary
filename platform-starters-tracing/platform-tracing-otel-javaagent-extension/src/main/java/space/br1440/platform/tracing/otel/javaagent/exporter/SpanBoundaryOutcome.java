package space.br1440.platform.tracing.otel.javaagent.exporter;

import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Результат {@link SpanDataBoundaryScrubber#scrub(SpanData)} для одного {@code SpanData}.
 * {@code dropSpan=true} означает, что этот конкретный span не должен покинуть процесс
 * (собственный collapse boundary упал — ADR §"Контракт ошибок", "DROP_SPAN").
 */
record SpanBoundaryOutcome(SpanData spanData, boolean dropSpan, boolean changed) {

    static SpanBoundaryOutcome unchanged(SpanData spanData) {
        return new SpanBoundaryOutcome(spanData, false, false);
    }

    static SpanBoundaryOutcome changed(SpanData spanData) {
        return new SpanBoundaryOutcome(spanData, false, true);
    }

    static SpanBoundaryOutcome drop() {
        return new SpanBoundaryOutcome(null, true, true);
    }
}
