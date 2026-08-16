package space.br1440.platform.tracing.otel.javaagent.processor;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import lombok.extern.slf4j.Slf4j;

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
 * <p>
 * Watchdog обеспечивает (неблокирующее поведение): если приложение «забыло» закрыть span,
 * процессор закрывает его автоматически, не позволяя памяти расти.
 *
 * <h2>Внутреннее состояние и инвариант</h2>
 * Единственный изменяемый владелец состояния индексирован по {@code traceId}. Запись трассы
 * содержит время её начала и вложенные записи span'ов по {@code spanId}. Регистрация, завершение
 * и сканирование изменяют запись только через атомарный {@code compute} внешней карты. Поэтому
 * одинаковые {@code spanId} в разных трассах не конфликтуют, а отдельные индексы и счётчики
 * активного состояния не требуются.
 */
@Slf4j
public final class SpanWatchdogProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

    private static final String DIAGNOSTIC_ID = "watchdog";

    private final long spanTimeoutNanos;
    private final long traceTimeoutNanos;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, TraceWatchdogState> activeTraces = new ConcurrentHashMap<>();
    private final AtomicLong forcedSpanCloses = new AtomicLong();
    private final AtomicLong forcedTraceCloses = new AtomicLong();

    public SpanWatchdogProcessor(Duration spanTimeout, Duration traceTimeout, Duration scanInterval) {
        this.spanTimeoutNanos = spanTimeout.toNanos();
        this.traceTimeoutNanos = traceTimeout.toNanos();
        this.nanoTime = System::nanoTime;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "platform-tracing-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        long intervalNanos = scanInterval.toNanos();
        this.scheduler.scheduleAtFixedRate(this::scanSafely, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
    }

    SpanWatchdogProcessor(Duration spanTimeout, Duration traceTimeout, LongSupplier nanoTime) {
        this.spanTimeoutNanos = spanTimeout.toNanos();
        this.traceTimeoutNanos = traceTimeout.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.scheduler = null;
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        String spanId = span.getSpanContext().getSpanId();
        String traceId = span.getSpanContext().getTraceId();

        long now = nanoTime.getAsLong();
        activeTraces.compute(traceId, (ignored, current) -> {
            TraceWatchdogState state = (current == null) ? new TraceWatchdogState(now) : current;
            Tracked tracked = new Tracked(spanId, new WeakReference<>(span), now);
            state.put(tracked);
            return state;
        });
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
        String spanId = span.getSpanContext().getSpanId();
        String traceId = span.getSpanContext().getTraceId();

        activeTraces.computeIfPresent(traceId, (ignored, state) -> {
            state.remove(spanId, span);
            return state.isEmpty() ? null : state;
        });
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        int leakedSpans = getActiveSpanCount();
        int leakedTraces = activeTraces.size();
        if (leakedSpans > 0 || leakedTraces > 0) {
            log.warn("SDK shutdown: {} unfinished spans remain across {} traces — possible missing span.end() in the application",
                    leakedSpans, leakedTraces);
        }

        activeTraces.clear();
        return CompletableResultCode.ofSuccess();
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
        int count = 0;
        for (TraceWatchdogState state : activeTraces.values()) {
            count += state.size();
        }
        return count;
    }

    public int getActiveTraceCount() {
        return activeTraces.size();
    }

    private void scanSafely() {
        try {
            scan();
        } catch (RuntimeException e) {
            log.warn("Watchdog background scan failed: {}", e.getMessage());
        }
    }

    private void scan() {
        long now = nanoTime.getAsLong();
        List<TimedOutSpan> timedOut = new ArrayList<>();
        for (String traceId : activeTraces.keySet()) {
            activeTraces.computeIfPresent(traceId, (ignored, state) -> {
                collectTimedOutSpans(traceId, state, now, timedOut);
                return state.isEmpty() ? null : state;
            });
        }

        for (TimedOutSpan candidate : timedOut) {
            closeTimedOutSpan(candidate);
        }
    }

    private void collectTimedOutSpans(String traceId,
                                      TraceWatchdogState state,
                                      long now,
                                      List<TimedOutSpan> timedOut) {
        boolean traceExpired = (now - state.startNanos()) > traceTimeoutNanos;
        for (Tracked tracked : state.records()) {
            ReadWriteSpan span = tracked.spanRef().get();
            if (span == null || span.hasEnded()) {
                state.remove(tracked);
                continue;
            }

            boolean spanExpired = (now - tracked.startNanos()) > spanTimeoutNanos;
            if ((spanExpired || traceExpired) && state.remove(tracked)) {
                timedOut.add(new TimedOutSpan(
                        span,
                        traceId,
                        spanExpired ? TimeoutReason.SPAN : TimeoutReason.TRACE)
                );
            }
        }
    }

    private void closeTimedOutSpan(TimedOutSpan candidate) {
        ReadWriteSpan span = candidate.span();
        try {
            span.setAttribute(PlatformAttributes.PLATFORM_TIMEOUT, candidate.reason().value());
            span.setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.TIMEOUT.value());
            span.setStatus(StatusCode.ERROR, "watchdog: " + candidate.reason().value() + " timeout");
            span.end();

            if (candidate.reason() == TimeoutReason.SPAN) {
                forcedSpanCloses.incrementAndGet();
            } else {
                forcedTraceCloses.incrementAndGet();
            }

            log.warn("Watchdog force-ended span '{}' (traceId={}, reason={})",
                    span.getName(), candidate.traceId(), candidate.reason().value());
        } catch (RuntimeException e) {
            log.warn("Watchdog failed to end span ({}): {}", candidate.reason().value(), e.getMessage());
        }
    }

    private static final class TraceWatchdogState {
        final long startNanos;
        private volatile Tracked single;
        private volatile ConcurrentHashMap<String, Tracked> multiple;

        TraceWatchdogState(long startNanos) {
            this.startNanos = startNanos;
        }

        long startNanos() {
            return startNanos;
        }

        void put(Tracked tracked) {
            ConcurrentHashMap<String, Tracked> current = multiple;
            if (current != null) {
                current.put(tracked.spanId(), tracked);
                return;
            }

            if (single == null || single.spanId().equals(tracked.spanId())) {
                single = tracked;
                return;
            }

            ConcurrentHashMap<String, Tracked> promoted = new ConcurrentHashMap<>();
            promoted.put(single.spanId(), single);
            promoted.put(tracked.spanId(), tracked);
            multiple = promoted;
            single = null;
        }

        void remove(String spanId, ReadableSpan expected) {
            Tracked currentSingle = single;
            if (currentSingle != null) {
                if (currentSingle.spanId().equals(spanId) && currentSingle.spanRef().get() == expected) {
                    single = null;
                }

                return;
            }

            ConcurrentHashMap<String, Tracked> current = multiple;
            if (current == null) {
                return;
            }

            Tracked tracked = current.get(spanId);
            if ((tracked != null) && (tracked.spanRef().get() == expected) && current.remove(spanId, tracked)) {
                collapseIfSingle(current);
            }
        }

        boolean remove(Tracked tracked) {
            if (single == tracked) {
                single = null;
                return true;
            }

            ConcurrentHashMap<String, Tracked> current = multiple;
            if (current == null || !current.remove(tracked.spanId(), tracked)) {
                return false;
            }

            collapseIfSingle(current);
            return true;
        }

        List<Tracked> records() {
            Tracked currentSingle = single;
            if (currentSingle != null) {
                return List.of(currentSingle);
            }

            ConcurrentHashMap<String, Tracked> current = multiple;
            return (current == null) ? List.of() : new ArrayList<>(current.values());
        }

        int size() {
            ConcurrentHashMap<String, Tracked> current = multiple;
            return (current == null) ? (single == null ? 0 : 1) : current.size();
        }

        boolean isEmpty() {
            return (size() == 0);
        }

        private void collapseIfSingle(ConcurrentHashMap<String, Tracked> current) {
            if (current.size() != 1) {
                return;
            }

            single = current.values().iterator().next();
            multiple = null;
        }
    }

    private record Tracked(String spanId, WeakReference<ReadWriteSpan> spanRef, long startNanos) {

        @Override
        public boolean equals(Object that) {
            return this == that;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    private record TimedOutSpan(ReadWriteSpan span, String traceId, TimeoutReason reason) {
    }

    private enum TimeoutReason {
        SPAN("span"),
        TRACE("trace");

        private final String value;

        TimeoutReason(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }
}
