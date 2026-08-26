package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.processor.ProcessorDiagnosticIdentity;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Сторожевой процессор, принудительно завершающий «зависшие» span'ы и trace'ы.
 * <p>
 * По SRE ADR устанавливается два таймаута:
 * <ul>
 *   <li>{@code spanTimeout} (по умолчанию 30 с) — максимальная длительность одного span'а.</li>
 *   <li>{@code traceTimeout} (по умолчанию 60 с) — максимальный возраст трассы; при превышении
 *       все ещё живые span'ы трассы принудительно закрываются.</li>
 * </ul>
 * При срабатывании таймаута на span проставляется атрибут
 * {@link PlatformAttributes#PLATFORM_TIMEOUT} со значением {@code span} или {@code trace} и
 * статус {@link StatusCode#ERROR} с описанием. Затем выполняется {@code span.end()}; экспорт идёт
 * по обычной цепочке процессоров.
 */
public final class SpanWatchdogProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(SpanWatchdogProcessor.class);

    private static final String DIAGNOSTIC_ID = "watchdog";
    private static final Runnable NO_BEFORE_COMPUTE_HOOK = () -> { };

    /**
     * Ограничение ожидания остановки сканера в {@link #shutdown()}. Без него результат
     * завершения сообщал бы об успехе, пока сканирование ещё работает с индексом,
     * который тут же дренируется.
     */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 2_000L;

    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final WatchdogTraceIndex index;
    private final AtomicLong forcedSpanCloses = new AtomicLong();
    private final AtomicLong forcedTraceCloses = new AtomicLong();
    private final AtomicBoolean admissionOpen = new AtomicBoolean(true);
    private final Runnable beforeComputeForTesting;

    public SpanWatchdogProcessor(Duration spanTimeout,
                                 Duration traceTimeout,
                                 Duration scanInterval,
                                 int maxTrackedTraces,
                                 int maxTrackedSpansPerTrace,
                                 int maxTrackedSpans) {
        this(spanTimeout, traceTimeout, System::nanoTime, maxTrackedTraces, maxTrackedSpansPerTrace, maxTrackedSpans,
                NO_BEFORE_COMPUTE_HOOK, Objects.requireNonNull(scanInterval, "scanInterval"));
    }

    SpanWatchdogProcessor(Duration spanTimeout,
                          Duration traceTimeout,
                          LongSupplier nanoTime,
                          int maxTrackedTraces,
                          int maxTrackedSpansPerTrace,
                          int maxTrackedSpans) {
        this(spanTimeout, traceTimeout, nanoTime, maxTrackedTraces, maxTrackedSpansPerTrace, maxTrackedSpans,
                NO_BEFORE_COMPUTE_HOOK);
    }

    SpanWatchdogProcessor(Duration spanTimeout,
                          Duration traceTimeout,
                          LongSupplier nanoTime,
                          int maxTrackedTraces,
                          int maxTrackedSpansPerTrace,
                          int maxTrackedSpans,
                          Runnable beforeComputeForTesting) {
        this(spanTimeout, traceTimeout, nanoTime, maxTrackedTraces, maxTrackedSpansPerTrace, maxTrackedSpans,
                beforeComputeForTesting, null);
    }

    private SpanWatchdogProcessor(Duration spanTimeout,
                                  Duration traceTimeout,
                                  LongSupplier nanoTime,
                                  int maxTrackedTraces,
                                  int maxTrackedSpansPerTrace,
                                  int maxTrackedSpans,
                                  Runnable beforeComputeForTesting,
                                  Duration scanInterval) {
        int trueMaxTrackedTraces = requirePositiveCapacity(maxTrackedTraces, "maxTrackedTraces");
        int trueMaxTrackedSpansPerTrace = requirePositiveCapacity(maxTrackedSpansPerTrace, "maxTrackedSpansPerTrace");
        int trueMaxTrackedSpans = requirePositiveCapacity(maxTrackedSpans, "maxTrackedSpans");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.beforeComputeForTesting = Objects.requireNonNull(beforeComputeForTesting, "beforeComputeForTesting");
        this.index = new WatchdogTraceIndex(
                spanTimeout.toNanos(),
                traceTimeout.toNanos(),
                trueMaxTrackedTraces,
                trueMaxTrackedSpansPerTrace,
                this.nanoTime,
                new SpanPermitLedger(trueMaxTrackedSpans)
        );

        if (scanInterval != null) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "platform-tracing-watchdog");
                thread.setDaemon(true);
                return thread;
            });

            long intervalNanos = scanInterval.toNanos();
            this.scheduler.scheduleAtFixedRate(this::scanSafely, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
        } else {
            this.scheduler = null;
        }
    }

    private static int requirePositiveCapacity(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }

        return value;
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        if (!admissionOpen.get()) {
            return;
        }

        beforeComputeForTesting.run();

        String spanId = span.getSpanContext().getSpanId();
        String traceId = span.getSpanContext().getTraceId();
        long now = nanoTime.getAsLong();

        index.admit(traceId, spanId, span, now, admissionOpen::get);
        index.evictOldestIfNeeded();
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
        String spanId = span.getSpanContext().getSpanId();
        String traceId = span.getSpanContext().getTraceId();
        index.onEnd(traceId, spanId, span);
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode shutdown() {
        admissionOpen.set(false);

        boolean scannerStopped = stopScanner();

        int leakedSpans = getActiveSpanCount();
        if (leakedSpans > 0) {
            log.warn(AgentEventCodes.EXPORT_WATCHDOG, "Unfinished spans remain at SDK shutdown",
                    "count", String.valueOf(leakedSpans));
        }

        index.drainAll();

        return scannerStopped ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
    }

    /**
     * Останавливает сканер и дожидается завершения текущего прохода. Дренаж индекса
     * выполняется только после этого, иначе сканирование и дренаж работали бы
     * с одним состоянием одновременно.
     */
    private boolean stopScanner() {
        if (scheduler == null) {
            return true;
        }

        scheduler.shutdownNow();
        try {
            if (scheduler.awaitTermination(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn(AgentEventCodes.EXPORT_WATCHDOG,
                    "Interrupted while waiting for the watchdog scanner to stop");
            return false;
        }

        log.warn(AgentEventCodes.EXPORT_WATCHDOG, "Watchdog scanner did not stop within the shutdown timeout",
                "timeoutMillis", String.valueOf(SHUTDOWN_TIMEOUT_MILLIS));
        return false;
    }

    @Override
    public CompletableResultCode forceFlush() {
        scanSafely();
        return CompletableResultCode.ofSuccess();
    }

    public long getForcedSpanCloses() {
        return forcedSpanCloses.get();
    }

    public long getForcedTraceCloses() {
        return forcedTraceCloses.get();
    }

    void scanForTesting() {
        scanSafely();
    }

    public int getActiveSpanCount() {
        return index.trackedSpanCount();
    }

    public int getActiveTraceCount() {
        return index.nonEmptyTraceCount();
    }

    public int getTrackedTraceCount() {
        return index.size();
    }

    public int getTrackedTraceCapacity() {
        return index.traceCapacity();
    }

    public long getTrackingEvictedTotal() {
        return index.trackingEvictedTotal();
    }

    public int getTrackedSpanCapacity() {
        return index.spanCapacity();
    }

    public int getTrackedSpanPerTraceCapacity() {
        return index.perTraceCapacity();
    }

    public long getSpansRejectedPerTraceTotal() {
        return index.rejectedPerTraceTotal();
    }

    public long getSpansRejectedGlobalTotal() {
        return index.rejectedGlobalTotal();
    }

    public long getSpanLedgerInvariantViolationTotal() {
        return index.ledgerInvariantViolationTotal();
    }

    private void scanSafely() {
        try {
            scan();
        } catch (RuntimeException e) {
            log.warn(AgentEventCodes.EXPORT_WATCHDOG, "Watchdog background scan failed", e);
        }
    }

    private void scan() {
        long now = nanoTime.getAsLong();
        List<WatchdogTraceIndex.ExpiredSpan> expired = index.collectExpired(now);
        for (WatchdogTraceIndex.ExpiredSpan candidate : expired) {
            closeTimedOutSpan(candidate);
        }
    }

    private void closeTimedOutSpan(WatchdogTraceIndex.ExpiredSpan candidate) {
        ReadWriteSpan span = candidate.span();
        try {
            span.setAttribute(PlatformAttributes.PLATFORM_TIMEOUT, candidate.reason().value());
            span.setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.TIMEOUT.value());
            span.setStatus(StatusCode.ERROR, "watchdog: " + candidate.reason().value() + " timeout");
            span.end();

            if (candidate.reason() == WatchdogTraceIndex.TimeoutReason.SPAN) {
                forcedSpanCloses.incrementAndGet();
            } else {
                forcedTraceCloses.incrementAndGet();
            }

            log.warn(AgentEventCodes.EXPORT_WATCHDOG, "Watchdog force-ended span",
                    "reason", candidate.reason().value());
        } catch (RuntimeException e) {
            log.warn(AgentEventCodes.EXPORT_WATCHDOG, "Watchdog force-ended span", e,
                    "reason", candidate.reason().value());
        }
    }
}
