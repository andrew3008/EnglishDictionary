package space.br1440.platform.tracing.otel.extension.safety;

import java.util.Objects;

/**
 * Координатор degraded-режима слоёв генерации и обработки данных (Фаза 11).
 * <p>
 * <b>Scope — только {@link Component#SAMPLER} и {@link Component#PROCESSOR}</b> (Вариант А,
 * ADR-safe-span-exporter-v1): экспортёр degraded-режимом не управляется — его деградация
 * обеспечивается drop-oldest очередью и Collector'ом (429/503), а не SDK-side circuit breaker'ом.
 *
 * <h2>Поведение</h2>
 * Под каждый компонент держится свой {@link CircuitBreaker}. При стабильных сбоях компонента breaker
 * открывается, компонент временно работает в degraded-режиме (sampler → fallback/last-known-good;
 * processor → пропуск проблемного делегата). Агрегированный признак degraded публикуется в
 * {@link TracingDiagnostics#setDegradedMode(boolean)} — он виден через JMX/actuator
 * (метрика {@code degraded_mode.enabled}).
 *
 * <p><b>Потокобезопасность:</b> делегирует атомарным {@link CircuitBreaker}; пересчёт агрегата
 * читает состояния обоих breaker'ов без блокировок.
 */
public final class DegradedModeController {

    /** Управляемые degraded-режимом компоненты (экспортёр сюда НЕ входит). */
    public enum Component { SAMPLER, PROCESSOR }

    private final CircuitBreaker samplerBreaker;
    private final CircuitBreaker processorBreaker;
    private final TracingDiagnostics diagnostics;

    public DegradedModeController(TracingDiagnostics diagnostics) {
        this(diagnostics,
                new CircuitBreaker("sampler"),
                new CircuitBreaker("processor"));
    }

    DegradedModeController(TracingDiagnostics diagnostics,
                           CircuitBreaker samplerBreaker,
                           CircuitBreaker processorBreaker) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.samplerBreaker = Objects.requireNonNull(samplerBreaker, "samplerBreaker");
        this.processorBreaker = Objects.requireNonNull(processorBreaker, "processorBreaker");
    }

    /** Состояние исполнения для компонента (с захватом probe в HALF_OPEN — вызывать раз на цикл). */
    public CircuitBreaker.State acquireExecutionState(Component component) {
        return breaker(component).acquireExecutionState();
    }

    public void recordSuccess(Component component) {
        breaker(component).recordSuccess();
        refreshAggregate();
    }

    public void recordFailure(Component component) {
        breaker(component).recordFailure();
        refreshAggregate();
    }

    /** {@code true}, если хотя бы один компонент сейчас деградирован (breaker не CLOSED). */
    public boolean isDegraded() {
        return samplerBreaker.getState() != CircuitBreaker.State.CLOSED
                || processorBreaker.getState() != CircuitBreaker.State.CLOSED;
    }

    private void refreshAggregate() {
        diagnostics.setDegradedMode(isDegraded());
    }

    private CircuitBreaker breaker(Component component) {
        return component == Component.SAMPLER ? samplerBreaker : processorBreaker;
    }
}
