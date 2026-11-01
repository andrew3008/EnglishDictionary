package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;

final class SpanOutcomeAccumulator {

    private int accepted;
    private int rejected;
    private int transportDropped;
    private int undetermined;
    private int locallyDropped;
    private int measurementFailed;
    @Nullable
    private Throwable measurementCause;

    void fold(ExportResponseObservation observation) {
        int count = observation.effectiveSpanCount();

        switch (observation.outcome()) {
            case ALL_ACCEPTED, NOT_OBSERVED -> accepted += count;
            case PARTIALLY_REJECTED -> {
                int rejectedCount = Math.toIntExact(observation.rejectedSpans());
                rejected += rejectedCount;
                accepted += (count - rejectedCount);
            }
            case NOT_DETERMINED -> undetermined += count;
        }
    }

    void dropOversized() {
        locallyDropped++;
    }

    void transportFailed(int count) {
        transportDropped += count;
    }

    void measurementFailed(Throwable cause) {
        measurementFailed++;

        if (measurementCause == null) {
            measurementCause = cause;
        }
    }

    int transportDropped() {
        return transportDropped;
    }

    ExportResponseObservation.AggregateResult toAggregate(int requests) {
        return new ExportResponseObservation.AggregateResult(
                accepted, rejected, transportDropped, undetermined, locallyDropped, measurementFailed,
                requests, measurementCause
        );
    }
}
