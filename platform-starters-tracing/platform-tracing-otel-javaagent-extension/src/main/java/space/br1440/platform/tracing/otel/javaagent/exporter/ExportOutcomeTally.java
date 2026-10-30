package space.br1440.platform.tracing.otel.javaagent.exporter;

import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;

/** Учёт исходов split-export по span. Сумма пяти счётчиков и {@code remaining} равна размеру batch. */
final class ExportOutcomeTally {

    private int remaining;
    private int accepted;
    private int rejected;
    private int transportDropped;
    private int undetermined;
    private int locallyDropped;

    ExportOutcomeTally(int total) {
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative: " + total);
        }
        this.remaining = total;
    }

    void fold(ExportResponseObservation observation) {
        int count = observation.effectiveSpanCount();
        switch (observation.outcome()) {
            case ALL_ACCEPTED, NOT_OBSERVED -> accepted += count;
            case PARTIALLY_REJECTED -> {
                int rejectedCount = Math.toIntExact(observation.rejectedSpans());
                rejected += rejectedCount;
                accepted += count - rejectedCount;
            }
            case NOT_DETERMINED -> undetermined += count;
        }
        remaining -= count;
    }

    void dropOversized() {
        locallyDropped++;
        remaining--;
    }

    void dropRemainingAsTransportFailure() {
        transportDropped += remaining;
        remaining = 0;
    }

    int remaining() {
        return remaining;
    }

    int transportDropped() {
        return transportDropped;
    }

    ExportResponseObservation.AggregateResult toAggregate() {
        return new ExportResponseObservation.AggregateResult(
                accepted, rejected, transportDropped, undetermined, locallyDropped);
    }
}
