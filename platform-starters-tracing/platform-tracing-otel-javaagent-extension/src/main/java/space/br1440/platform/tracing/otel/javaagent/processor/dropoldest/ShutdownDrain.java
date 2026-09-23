package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.List;
import java.util.Objects;

import io.opentelemetry.sdk.common.CompletableResultCode;

final class ShutdownDrain {

    private final long startedAtNanos;
    private final long timeoutNanos;
    private final CompletableResultCode flushResult;
    private Outcome outcome = Outcome.PENDING;

    ShutdownDrain(long startedAtNanos, long timeoutNanos, CompletableResultCode flushResult) {
        this.startedAtNanos = startedAtNanos;
        this.timeoutNanos = timeoutNanos;
        this.flushResult = Objects.requireNonNull(flushResult, "flushResult");
    }

    boolean isDeadlineExceeded(long nowNanos) {
        return (nowNanos - startedAtNanos) >= timeoutNanos;
    }

    long remainingNanos(long nowNanos) {
        long elapsedNanos = nowNanos - startedAtNanos;
        if (elapsedNanos <= 0L) {
            return timeoutNanos;
        }

        if (elapsedNanos >= timeoutNanos) {
            return 0L;
        }

        return timeoutNanos - elapsedNanos;
    }

    void markDeadlineExceeded() {
        outcome = Outcome.DEADLINE_EXCEEDED;
    }

    void captureFlushOutcome(List<FlushTracker.FlushCompletion> completions) {
        if (outcome == Outcome.DEADLINE_EXCEEDED) {
            return;
        }

        for (FlushTracker.FlushCompletion completion : completions) {
            if (completion.result() == flushResult) {
                outcome = completion.success() ? Outcome.COMPLETED : Outcome.FLUSH_FAILED;
                return;
            }
        }
    }

    WorkerStopReason stopReason() {
        return switch (outcome) {
            case COMPLETED -> WorkerStopReason.DRAIN_COMPLETED;
            case FLUSH_FAILED -> WorkerStopReason.SHUTDOWN_FLUSH_FAILED;
            case DEADLINE_EXCEEDED -> WorkerStopReason.DRAIN_DEADLINE_EXCEEDED;
            case PENDING -> throw new IllegalStateException("Shutdown drain became idle before its flush generation completed");
        };
    }

    private enum Outcome {
        PENDING,
        COMPLETED,
        FLUSH_FAILED,
        DEADLINE_EXCEEDED
    }
}
