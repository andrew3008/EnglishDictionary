package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.MinIntervalRateLimiter;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.sdk.trace.ReadWriteSpan;

final class SpanTimeoutScan {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(SpanTimeoutScan.class);

    private static final long SCAN_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final InFlightTraceIndex index;
    private final LongSupplier nanoTime;
    private final MinIntervalRateLimiter scanLog;
    private final AtomicLong forcedSpanEndAttemptsTotal = new AtomicLong();
    private final AtomicLong forcedTraceEndAttemptsTotal = new AtomicLong();
    private final AtomicLong scanFailuresTotal = new AtomicLong();
    private final AtomicLong forceEndFailuresTotal = new AtomicLong();

    SpanTimeoutScan(InFlightTraceIndex index, LongSupplier nanoTime) {
        this.index = Objects.requireNonNull(index, "index");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.scanLog = new MinIntervalRateLimiter(SCAN_LOG_INTERVAL_NANOS, this.nanoTime);
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
            PlatformThrowables.propagateIfFatal(failure);
            outcome.failed = true;
            log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_SCAN_STAGE_FAILED, fields -> fields
                    .putString(AgentLogField.STAGE, stage)
                    .cause(failure));
        }
    }

    private void forceEndAll(ArrayDeque<ExpiredSpan> batch, ScanOutcome outcome) {
        ExpiredSpan candidate;
        while ((candidate = batch.poll()) != null) {
            try {
                forceEnd(candidate, outcome);
            } catch (Throwable failure) {
                PlatformThrowables.propagateIfFatal(failure);
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
        log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_FORCE_END_FAILED, fields -> fields
                .putString(AgentLogField.TRACE_ID, candidate.traceId())
                .putString(AgentLogField.SPAN_ID, candidate.spanId())
                .putString(AgentLogField.REASON, candidate.reason().value())
                .putString(AgentLogField.FAILURE_TYPE, failure.getClass().getName()));
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

        log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_FORCE_ENDED, fields -> fields
                .putString(AgentLogField.TRACE_ID, candidate.traceId())
                .putString(AgentLogField.SPAN_ID, candidate.spanId())
                .putString(AgentLogField.REASON, candidate.reason().value()));
    }

    private void logScanOutcome(ScanOutcome outcome) {
        if ((outcome.spanAttempts + outcome.traceAttempts + outcome.failures) == 0) {
            return;
        }

        if (!scanLog.tryAcquire()) {
            return;
        }

        if (outcome.failures == 0) {
            log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_FORCE_END_SUMMARY, fields -> fields
                    .putLong(AgentLogField.SPAN_TIMEOUT_ATTEMPTS, outcome.spanAttempts)
                    .putLong(AgentLogField.TRACE_TIMEOUT_ATTEMPTS, outcome.traceAttempts)
                    .putLong(AgentLogField.FAILURES, 0L)
                    .putString(AgentLogField.SAMPLE_TRACE_ID, outcome.sampleTraceId)
                    .putString(AgentLogField.SAMPLE_SPAN_ID, outcome.sampleSpanId));
            return;
        }

        log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_FORCE_END_SUMMARY_WITH_FAILURES, fields -> fields
                .putLong(AgentLogField.SPAN_TIMEOUT_ATTEMPTS, outcome.spanAttempts)
                .putLong(AgentLogField.TRACE_TIMEOUT_ATTEMPTS, outcome.traceAttempts)
                .putLong(AgentLogField.FAILURES, outcome.failures)
                .putString(AgentLogField.SAMPLE_TRACE_ID, outcome.failureTraceId)
                .putString(AgentLogField.SAMPLE_SPAN_ID, outcome.failureSpanId)
                .putString(AgentLogField.FAILURE_SAMPLE, outcome.failureSample));
    }

    private static final class ScanOutcome {

        private boolean failed;
        private int spanAttempts;
        private int traceAttempts;
        private int failures;
        private String sampleTraceId = "";
        private String sampleSpanId = "";
        private String failureTraceId = "";
        private String failureSpanId = "";
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

            if (failureSample.isEmpty()) {
                failureTraceId = candidate.traceId();
                failureSpanId = candidate.spanId();
                failureSample = failure.getClass().getName();
            }
        }
    }
}
