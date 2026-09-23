package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import io.opentelemetry.sdk.trace.ReadWriteSpan;

final class SpanTimeoutScan {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(SpanTimeoutScan.class);

    private static final long SCAN_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final InFlightTraceIndex index;
    private final LongSupplier nanoTime;
    private final LogRateLimiter scanLog;
    private final AtomicLong forcedSpanEndAttemptsTotal = new AtomicLong();
    private final AtomicLong forcedTraceEndAttemptsTotal = new AtomicLong();
    private final AtomicLong scanFailuresTotal = new AtomicLong();
    private final AtomicLong forceEndFailuresTotal = new AtomicLong();

    SpanTimeoutScan(InFlightTraceIndex index, LongSupplier nanoTime) {
        this.index = Objects.requireNonNull(index, "index");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.scanLog = new LogRateLimiter(SCAN_LOG_INTERVAL_NANOS, this.nanoTime);
    }

    boolean run() {
        ArrayDeque<ExpiredSpan> batch = new ArrayDeque<>();
        ScanOutcome outcome = new ScanOutcome();
        boolean completed = false;
        try {
            runStage("relief", outcome, index::relieveTracePressure);
            runStage("collect", outcome, () -> index.collectExpired(nanoTime.getAsLong(), batch));
            forceEndAll(batch, outcome);
            completed = true;
        } finally {
            releaseUnprocessed(batch);
            recordScanResult(completed, outcome);
        }

        return !outcome.failed;
    }

    long forcedSpanEndAttemptsTotal() {
        return forcedSpanEndAttemptsTotal.get();
    }

    long forcedTraceEndAttemptsTotal() {
        return forcedTraceEndAttemptsTotal.get();
    }

    long scanFailuresTotal() {
        return scanFailuresTotal.get();
    }

    long forceEndFailuresTotal() {
        return forceEndFailuresTotal.get();
    }

    private static void runStage(String stage, ScanOutcome outcome, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            outcome.failed = true;
            log.warn(AgentEventCode.EXPORT_SPAN_TIMEOUT, "Span-timeout scan stage failed", failure,
                    "stage", stage);
        }
    }

    private void forceEndAll(ArrayDeque<ExpiredSpan> batch, ScanOutcome outcome) {
        ExpiredSpan candidate;
        while ((candidate = batch.poll()) != null) {
            try {
                forceEnd(candidate, outcome);
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
                recordForceEndFailure(candidate, failure, outcome);
            } finally {
                index.releaseCandidatePermit(candidate, "force-end");
            }
        }
    }

    private void recordForceEndFailure(ExpiredSpan candidate,
                                       Throwable failure,
                                       ScanOutcome outcome) {
        outcome.recordFailure(candidate, failure);
        forceEndFailuresTotal.incrementAndGet();
        log.debug(AgentEventCode.EXPORT_SPAN_TIMEOUT, "Span timeout failed to force-end span",
                "traceId", candidate.traceId(),
                "spanId", candidate.spanId(),
                "reason", candidate.reason().value(),
                "failureType", failure.getClass().getName());
    }

    private void releaseUnprocessed(ArrayDeque<ExpiredSpan> batch) {
        ExpiredSpan rest;
        while ((rest = batch.poll()) != null) {
            index.releaseCandidatePermit(rest, "scan-abort");
        }
    }

    private void recordScanResult(boolean completed, ScanOutcome outcome) {
        if (!completed || outcome.failed) {
            scanFailuresTotal.incrementAndGet();
        }

        if (completed) {
            logScanOutcome(outcome);
        }
    }

    private void forceEnd(ExpiredSpan candidate, ScanOutcome outcome) {
        ReadWriteSpan span = candidate.span();
        span.setAttribute(PlatformAttributes.PLATFORM_TIMEOUT, candidate.reason().value());
        span.setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.TIMEOUT.value());

        if (candidate.reason() == TimeoutReason.SPAN) {
            forcedSpanEndAttemptsTotal.incrementAndGet();
            outcome.spanAttempts++;
        } else {
            forcedTraceEndAttemptsTotal.incrementAndGet();
            outcome.traceAttempts++;
        }

        outcome.sample(candidate);
        span.end();

        log.debug(AgentEventCode.EXPORT_SPAN_TIMEOUT, "Span timeout force-ended span",
                "traceId", candidate.traceId(),
                "spanId", candidate.spanId(),
                "reason", candidate.reason().value());
    }

    private void logScanOutcome(ScanOutcome outcome) {
        if ((outcome.spanAttempts + outcome.traceAttempts + outcome.failures) == 0) {
            return;
        }

        if (!scanLog.tryAcquire()) {
            return;
        }

        if (outcome.failures == 0) {
            log.info(AgentEventCode.EXPORT_SPAN_TIMEOUT, "Span timeout force-ended spans",
                    "spanTimeoutAttempts", String.valueOf(outcome.spanAttempts),
                    "traceTimeoutAttempts", String.valueOf(outcome.traceAttempts),
                    "failures", "0",
                    "sampleTraceId", outcome.sampleTraceId,
                    "sampleSpanId", outcome.sampleSpanId);
            return;
        }

        log.warn(AgentEventCode.EXPORT_SPAN_TIMEOUT, "Span timeout force-ended spans with failures",
                "spanTimeoutAttempts", String.valueOf(outcome.spanAttempts),
                "traceTimeoutAttempts", String.valueOf(outcome.traceAttempts),
                "failures", String.valueOf(outcome.failures),
                "sampleTraceId", outcome.sampleTraceId,
                "sampleSpanId", outcome.sampleSpanId,
                "failureSample", outcome.failureSample);
    }

    private static void rethrowIfFatal(Throwable failure) {
        if (isFatal(failure)) {
            throw (Error) failure;
        }
    }

    private static boolean isFatal(Throwable failure) {
        return (failure instanceof VirtualMachineError)
                || (failure instanceof LinkageError);
    }

    private static final class ScanOutcome {

        private boolean failed;
        private int spanAttempts;
        private int traceAttempts;
        private int failures;
        private String sampleTraceId = "";
        private String sampleSpanId = "";
        private String failureSample = "";

        private void sample(ExpiredSpan candidate) {
            if (sampleTraceId.isEmpty()) {
                sampleTraceId = candidate.traceId();
                sampleSpanId = candidate.spanId();
            }
        }

        private void recordFailure(ExpiredSpan candidate, Throwable failure) {
            failed = true;
            failures++;

            sample(candidate);
            failureSample = failure.getClass().getName();
        }
    }
}
