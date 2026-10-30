package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import java.util.Objects;
import jakarta.annotation.Nullable;

public final class ExportResponseObservation {

    private final int effectiveSpanCount;

    private Outcome outcome = Outcome.NOT_OBSERVED;
    private long rejectedSpans;
    @Nullable
    private AggregateResult aggregateResult;

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

    public synchronized void recordAggregate(AggregateResult result) {
        Objects.requireNonNull(result, "result");

        if (aggregateResult != null || outcome != Outcome.NOT_OBSERVED) {
            throw new IllegalStateException("Export outcome has already been recorded");
        }

        if (result.total() != effectiveSpanCount) {
            throw new IllegalArgumentException("Aggregate count must equal the effective span count");
        }

        aggregateResult = result;
    }

    @Nullable
    public synchronized AggregateResult aggregateResult() {
        return aggregateResult;
    }

    private void record(Outcome observed, long rejected) {
        if (aggregateResult != null) {
            throw new IllegalStateException("Aggregate export outcome has already been recorded");
        }

        if (outcome != Outcome.NOT_OBSERVED) {
            outcome = Outcome.NOT_DETERMINED;
            rejectedSpans = 0L;
            return;
        }

        outcome = observed;
        rejectedSpans = rejected;
    }

    public record AggregateResult(int accepted,
                                  int rejected,
                                  int transportDropped,
                                  int undetermined,
                                  int locallyDropped) {

        public AggregateResult {
            if (accepted < 0 || rejected < 0 || transportDropped < 0 || undetermined < 0 || locallyDropped < 0) {
                throw new IllegalArgumentException("Aggregate span counts must not be negative");
            }
        }

        public long total() {
            return (long) accepted + rejected + transportDropped + undetermined + locallyDropped;
        }
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
