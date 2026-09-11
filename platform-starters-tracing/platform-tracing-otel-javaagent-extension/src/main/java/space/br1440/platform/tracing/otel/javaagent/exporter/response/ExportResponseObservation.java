package space.br1440.platform.tracing.otel.javaagent.exporter.response;

public final class ExportResponseObservation {

    private final int effectiveSpanCount;

    private Outcome outcome = Outcome.NOT_OBSERVED;
    private long rejectedSpans;

    public ExportResponseObservation(int effectiveSpanCount) {
        if (effectiveSpanCount < 0) {
            throw new IllegalArgumentException("effectiveSpanCount must not be negative");
        }

        this.effectiveSpanCount = effectiveSpanCount;
    }

    public int effectiveSpanCount() {
        return effectiveSpanCount;
    }

    public synchronized void recordAllAccepted() {
        record(Outcome.ALL_ACCEPTED, 0L);
    }

    public synchronized void recordRejected(long rejected) {
        if (rejected <= 0L || rejected > effectiveSpanCount) {
            record(Outcome.NOT_DETERMINED, 0L);
            return;
        }

        record(Outcome.PARTIALLY_REJECTED, rejected);
    }

    public synchronized void recordNotDetermined() {
        record(Outcome.NOT_DETERMINED, 0L);
    }

    public synchronized Outcome outcome() {
        return outcome;
    }

    public synchronized long rejectedSpans() {
        return rejectedSpans;
    }

    public synchronized long acceptedSpans() {
        return (effectiveSpanCount - rejectedSpans);
    }

    private void record(Outcome observed, long rejected) {
        if (outcome != Outcome.NOT_OBSERVED) {
            outcome = Outcome.NOT_DETERMINED;
            rejectedSpans = 0L;
            return;
        }

        outcome = observed;
        rejectedSpans = rejected;
    }

    public enum Outcome {

        /** OTLP-ответ не наблюдался; экспортёр не оборачивается наблюдающим sender'ом. */
        NOT_OBSERVED,

        /** Валидный успешный ответ, в котором не отклонено ни одного span'а. */
        ALL_ACCEPTED,

        /** Валидный успешный ответ, в котором отклонена часть батча. */
        PARTIALLY_REJECTED,

        /** Ответ получен, но число принятых span'ов установить не удалось. */
        NOT_DETERMINED
    }
}
