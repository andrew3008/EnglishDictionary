package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Сторожевой процессор, принудительно завершающий «зависшие» span'ы и trace'ы.
 * <p>
 * Платформенный стандарт §31 устанавливает два таймаута:
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
 * Watchdog обеспечивает §37 (неблокирующее поведение): если приложение «забыло» закрыть span,
 * процессор закрывает его автоматически, не позволяя памяти расти.
 *
 * <h2>Внутреннее состояние и инвариант</h2>
 * Состояние составное: {@code activeSpans} (живые span'ы по spanId), {@code traceStartByTraceId}
 * (метка старта трассы) и {@code liveSpanCountByTrace} (счётчик живых span'ов в трассе).
 * Инвариант «трасса видна в {@code traceStartByTraceId} тогда и только тогда, когда
 * {@code liveSpanCountByTrace} > 0» защищается через {@link ConcurrentMap#compute(Object, java.util.function.BiFunction)}:
 * декремент счётчика и удаление трассы выполняются в одном атомарном шаге, поэтому
 * {@code forceFlush()} (синхронный scan) и фоновый scheduler-tick могут безопасно конкурировать
 * без явного локирования.
 */
public final class SpanWatchdogProcessor implements ExtendedSpanProcessor {

    private static final Logger log = LoggerFactory.getLogger(SpanWatchdogProcessor.class);

    private final long spanTimeoutNanos;
    private final long traceTimeoutNanos;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, Tracked> activeSpans = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> traceStartByTraceId = new ConcurrentHashMap<>();
    /**
     * Вторичный индекс: число живых span'ов в трассе.
     * <p>
     * Существует ради O(1) удаления трассы из {@link #traceStartByTraceId} при завершении
     * её последнего span'а (без линейного прохода по {@link #activeSpans}). Декремент идёт
     * через {@code compute()}, что атомарно проверяет «счётчик == 0» и удаляет ключ — в этом
     * же шаге освобождается и запись в {@link #traceStartByTraceId}.
     */
    private final ConcurrentMap<String, Long> liveSpanCountByTrace = new ConcurrentHashMap<>();
    private final AtomicLong forcedSpanCloses = new AtomicLong();
    private final AtomicLong forcedTraceCloses = new AtomicLong();

    public SpanWatchdogProcessor(Duration spanTimeout, Duration traceTimeout, Duration scanInterval) {
        this.spanTimeoutNanos = spanTimeout.toNanos();
        this.traceTimeoutNanos = traceTimeout.toNanos();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "platform-tracing-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        long intervalNanos = Math.max(1, scanInterval.toNanos());
        this.scheduler.scheduleAtFixedRate(this::scanSafely, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Конструктор для unit-тестов: позволяет внедрить уже подготовленный планировщик и отключить
     * фоновое сканирование (управляя им вручную через {@link #scanForTesting()}).
     */
    SpanWatchdogProcessor(Duration spanTimeout, Duration traceTimeout) {
        this.spanTimeoutNanos = spanTimeout.toNanos();
        this.traceTimeoutNanos = traceTimeout.toNanos();
        this.scheduler = null;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        String spanId = span.getSpanContext().getSpanId();
        String traceId = span.getSpanContext().getTraceId();
        long now = System.nanoTime();
        activeSpans.put(spanId, new Tracked(span, now, traceId));
        traceStartByTraceId.computeIfAbsent(traceId, k -> new AtomicLong(now));
        // Инкремент счётчика живых span'ов трассы в одном атомарном шаге с возможной
        // регистрацией трассы. На текущем span'е счётчик становится >= 1, что соответствует
        // только что вставленной записи в traceStartByTraceId.
        liveSpanCountByTrace.merge(traceId, 1L, Long::sum);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        String spanId = span.getSpanContext().getSpanId();
        String traceId = span.getSpanContext().getTraceId();
        activeSpans.remove(spanId);
        decrementTraceLiveCount(traceId);
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        // Watchdog не нуждается в onEnding-фазе: его сигналы — onStart (регистрация),
        // onEnd (снятие) и фоновое сканирование. Метод оставлен пустым для унификации API
        // с другими нашими процессорами (REFACTOR-5).
    }

    @Override
    public boolean isOnEndingRequired() {
        return false;
    }

    @Override
    public CompletableResultCode shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        // Утечка незакрытых span'ов на shutdown — диагностический сигнал: приложение либо
        // не успело корректно завершить in-flight запросы (force kill / OOM), либо имеет баг
        // с забытым span.end(). Логируем до очистки, чтобы оператор увидел масштаб.
        int leakedSpans = activeSpans.size();
        int leakedTraces = traceStartByTraceId.size();
        if (leakedSpans > 0 || leakedTraces > 0) {
            log.warn("SDK shutdown: остались {} незакрытых span'ов в {} трассах — возможна утечка span.end() в приложении",
                    leakedSpans, leakedTraces);
        }
        activeSpans.clear();
        traceStartByTraceId.clear();
        liveSpanCountByTrace.clear();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
        // §31/§37: на graceful shutdown SDK вызывает forceFlush() перед shutdown(). Watchdog
        // обязан до завершения экспорта дозакрыть зависшие span'ы — иначе они потеряются.
        // Race с фоновым scheduler-tick'ом исключён за счёт атомарного compute() на
        // liveSpanCountByTrace в decrementTraceLiveCount(...).
        scanSafely();
        return CompletableResultCode.ofSuccess();
    }

    /** Количество принудительно закрытых span'ов по таймауту span'а — для метрик/диагностики. */
    public long getForcedSpanCloses() {
        return forcedSpanCloses.get();
    }

    /** Количество принудительно закрытых span'ов по таймауту trace'а. */
    public long getForcedTraceCloses() {
        return forcedTraceCloses.get();
    }

    /** Принудительный запуск сканирования — вызывается фоновым планировщиком и unit-тестами. */
    void scanForTesting() {
        scanSafely();
    }

    /**
     * Текущее число живых (ещё не закрытых приложением) span'ов, отслеживаемых watchdog'ом.
     * Используется JMX-мостом для публикации stats оператору.
     */
    public int getActiveSpanCount() {
        return activeSpans.size();
    }

    /** Текущее число живых трасс, отслеживаемых watchdog'ом. Используется JMX-мостом. */
    public int getActiveTraceCount() {
        return traceStartByTraceId.size();
    }

    /**
     * Размер вторичного индекса счётчиков {@code liveSpanCountByTrace} — для concurrency-тестов
     * (валидация инварианта BUG-3).
     */
    int liveTraceCountIndexSizeForTesting() {
        return liveSpanCountByTrace.size();
    }

    /**
     * Атомарный декремент счётчика живых span'ов трассы.
     * <p>
     * Если после декремента счётчик становится {@code <= 0}, лямбда возвращает {@code null} —
     * {@link ConcurrentHashMap} удаляет ключ. В этом же атомарном шаге снимается запись в
     * {@link #traceStartByTraceId}: оба удаления не могут «разъехаться» между concurrent-вызовами
     * из {@code onEnd} и {@code scanSafely}.
     */
    private void decrementTraceLiveCount(String traceId) {
        liveSpanCountByTrace.compute(traceId, (k, count) -> {
            long newCount = (count == null ? 0L : count) - 1L;
            if (newCount <= 0L) {
                traceStartByTraceId.remove(traceId);
                return null;
            }
            return newCount;
        });
    }

    private void scanSafely() {
        try {
            scan();
        } catch (RuntimeException e) {
            log.warn("Ошибка фонового сканирования watchdog: {}", e.getMessage());
        }
    }

    private void scan() {
        long now = System.nanoTime();
        Set<String> expiredTraces = collectExpiredTraces(now);

        Iterator<Map.Entry<String, Tracked>> it = activeSpans.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Tracked> entry = it.next();
            Tracked tracked = entry.getValue();
            ReadWriteSpan span = tracked.spanRef.get();

            if (span == null || span.hasEnded()) {
                it.remove();
                // GC собрал span либо приложение успело закрыть его раньше watchdog'а:
                // вторичный индекс должен отразить факт исчезновения этого span'а.
                decrementTraceLiveCount(tracked.traceId);
                continue;
            }

            boolean spanExpired = (now - tracked.startNanos) > spanTimeoutNanos;
            boolean traceExpired = expiredTraces.contains(tracked.traceId);
            if (!spanExpired && !traceExpired) {
                continue;
            }

            String reason = spanExpired ? "span" : "trace";
            try {
                span.setAttribute(PlatformAttributes.PLATFORM_TIMEOUT, reason);
                span.setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.TIMEOUT.value());
                span.setStatus(StatusCode.ERROR, "watchdog: " + reason + " timeout");
                span.end();
                if (spanExpired) {
                    forcedSpanCloses.incrementAndGet();
                } else {
                    forcedTraceCloses.incrementAndGet();
                }
                log.warn("Watchdog принудительно завершил span '{}' (traceId={}, причина={})",
                        span.getName(), tracked.traceId, reason);
            } catch (RuntimeException e) {
                log.warn("Не удалось закрыть span watchdog'ом ({}): {}", reason, e.getMessage());
            } finally {
                it.remove();
                // span.end() триггерит наш onEnd(...) и сам декрементирует счётчик через
                // decrementTraceLiveCount(...). Дополнительный декремент здесь привёл бы к
                // отрицательным значениям и преждевременному удалению трассы.
            }
        }
    }

    private Set<String> collectExpiredTraces(long now) {
        Set<String> expired = new HashSet<>();
        for (Map.Entry<String, AtomicLong> entry : traceStartByTraceId.entrySet()) {
            if ((now - entry.getValue().get()) > traceTimeoutNanos) {
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    private static final class Tracked {
        final WeakReference<ReadWriteSpan> spanRef;
        final long startNanos;
        final String traceId;

        Tracked(ReadWriteSpan span, long startNanos, String traceId) {
            this.spanRef = new WeakReference<>(span);
            this.startNanos = startNanos;
            this.traceId = traceId;
        }
    }
}
