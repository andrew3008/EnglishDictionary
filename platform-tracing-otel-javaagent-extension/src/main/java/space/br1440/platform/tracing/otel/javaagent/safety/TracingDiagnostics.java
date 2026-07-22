package space.br1440.platform.tracing.otel.javaagent.safety;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Единый фасад диагностических счётчиков safe-обёрток платформы (Фаза 11).
 * <p>
 * <b>Принцип:</b> silently handled ошибка для бизнес-логики не означает «невидимая» для platform-команды.
 * Любая ошибка, подавленная safe-обёрткой, инкрементит low-cardinality счётчик здесь и становится
 * наблюдаемой через JMX/actuator (и далее в Prometheus через polling-MeterBinder в Spring-стартере).
 *
 * <h2>Реализация — Вариант А (ADR-processor-errors-metric)</h2>
 * Счётчики — это {@link LongAdder}/{@link AtomicLong} в classloader'е Java Agent extension.
 * <b>Никаких прямых вызовов Micrometer-API здесь нет</b> — {@code MeterRegistry} живёт в Application
 * classloader и недоступен агенту ({@code NoClassDefFoundError}). Экспозиция в Prometheus —
 * через JMX MBean + polling {@code FunctionCounter}/{@code Gauge} на стороне Spring Boot стартера.
 *
 * <h2>Scope «degraded_mode»</h2>
 * Флаг degraded относится только к слоям генерации/обработки (Sampler, SpanProcessor) — экспортёр
 * не управляется degraded-режимом (Вариант А, ADR-safe-span-exporter-v1).
 *
 * <p><b>Потокобезопасность:</b> все поля атомарны; класс рассчитан на конкурентную запись с hot-path
 * и конкурентное чтение метрик.
 */
public final class TracingDiagnostics {

    /**
     * Процессный экземпляр по умолчанию. В Java Agent extension живёт один classloader, поэтому
     * один общий инстанс корректно агрегирует диагностику всех safe-обёрток. Компоненты, которые
     * сложно протянуть через конструкторы (создаются фабриками SPI), используют {@link #shared()};
     * в юнит-тестах можно создать изолированный экземпляр через {@code new TracingDiagnostics()}.
     */
    private static final TracingDiagnostics SHARED = new TracingDiagnostics();

    private final LongAdder samplerFailures = new LongAdder();
    private final LongAdder propagatorFailures = new LongAdder();
    private final LongAdder resourceFailures = new LongAdder();
    private final LongAdder scopeFailures = new LongAdder();
    /** Суммарно подавленных ошибок по всем safe-обёрткам (надмножество специфичных счётчиков). */
    private final LongAdder suppressedErrors = new LongAdder();

    private final AtomicBoolean degradedModeEnabled = new AtomicBoolean(false);
    private final AtomicLong lastFailureEpochMs = new AtomicLong(0L);

    public static TracingDiagnostics shared() {
        return SHARED;
    }

    public void recordSamplerFailure() {
        samplerFailures.increment();
        onAnyFailure();
    }

    public void recordPropagatorFailure() {
        propagatorFailures.increment();
        onAnyFailure();
    }

    public void recordResourceFailure() {
        resourceFailures.increment();
        onAnyFailure();
    }

    public void recordScopeFailure() {
        scopeFailures.increment();
        onAnyFailure();
    }

    private void onAnyFailure() {
        suppressedErrors.increment();
        lastFailureEpochMs.set(System.currentTimeMillis());
    }

    /** Включает/выключает признак degraded-режима (Sampler/Processor). */
    public void setDegradedMode(boolean enabled) {
        degradedModeEnabled.set(enabled);
    }

    public boolean isDegradedMode() {
        return degradedModeEnabled.get();
    }

    public long getSamplerFailures() {
        return samplerFailures.sum();
    }

    public long getPropagatorFailures() {
        return propagatorFailures.sum();
    }

    public long getResourceFailures() {
        return resourceFailures.sum();
    }

    public long getScopeFailures() {
        return scopeFailures.sum();
    }

    public long getSuppressedErrors() {
        return suppressedErrors.sum();
    }

    public long getLastFailureEpochMs() {
        return lastFailureEpochMs.get();
    }

    /**
     * Снимок счётчиков для JMX/actuator. Ключи стабильны и low-cardinality.
     */
    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("sampler.failures", getSamplerFailures());
        snapshot.put("propagator.failures", getPropagatorFailures());
        snapshot.put("resource.failures", getResourceFailures());
        snapshot.put("scope.failures", getScopeFailures());
        snapshot.put("safe_wrapper.suppressed_errors", getSuppressedErrors());
        snapshot.put("degraded_mode.enabled", degradedModeEnabled.get() ? 1L : 0L);
        snapshot.put("last_failure.epoch_ms", getLastFailureEpochMs());
        return snapshot;
    }
}
