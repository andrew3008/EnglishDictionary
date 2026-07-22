package space.br1440.platform.tracing.otel.extension.scrubbing.circuitbreaker;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker одного правила scrubbing'а с автоматом CLOSED → OPEN → HALF_OPEN.
 * <p>
 * Защищает горячий путь {@code onEnding} от «log storm» и GC-давления, когда пользовательское
 * правило стабильно падает на каждом span'е: после порога ошибок правило временно отключается
 * (OPEN), а по истечении cooldown допускается ровно один пробный вызов (HALF_OPEN).
 *
 * <h3>Переходы состояний</h3>
 * <pre>
 *  CLOSED ──(N ошибок подряд)──▶ OPEN ──(прошёл cooldownMs)──▶ HALF_OPEN
 *    ▲                                                              │
 *    └──────────────(probe успешен)─────────────────────────────────┘
 *    OPEN  ◀──(probe упал)───────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Конкурентность HALF_OPEN</h3>
 * В состоянии HALF_OPEN ровно <b>один</b> поток получает право на пробный вызов; остальные
 * конкурентные потоки видят состояние как OPEN, пока probe не разрешится. Это гарантируется
 * атомарным CAS-семафором {@link #probeInProgress}.
 *
 * <h3>Rate-limited логирование</h3>
 * Ошибки логируются не чаще одного раза в {@code logThrottleMs}, без сырых значений/ключей.
 *
 * <h3>Потокобезопасность</h3>
 * Все поля атомарны; экземпляр безопасно используется из нескольких потоков экспорта.
 */
@Slf4j
public final class RuleCircuitBreaker {

    /** Состояние автомата. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String ruleName;
    private final int failureThreshold;
    private final long cooldownMs;
    private final long logThrottleMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0);
    private final AtomicLong lastLoggedMs = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);

    /** Семафор пробного вызова: 0 — probe не идёт, 1 — probe захвачен одним потоком. */
    private final AtomicInteger probeInProgress = new AtomicInteger(0);

    /** Конструктор с дефолтами: порог 5 ошибок, cooldown 60с, throttle лога 60с. */
    public RuleCircuitBreaker(String ruleName) {
        this(ruleName, 5, 60_000L, 60_000L);
    }

    public RuleCircuitBreaker(String ruleName, int failureThreshold, long cooldownMs, long logThrottleMs) {
        this.ruleName = ruleName;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.logThrottleMs = logThrottleMs;
    }

    /**
     * Возвращает состояние, в котором текущий поток должен исполнять правило, и при необходимости
     * захватывает право на HALF_OPEN-probe.
     * <p>
     * <b>ВНИМАНИЕ — это команда, а не запрос (нарушение CQS осознанное):</b> при возврате
     * {@link State#HALF_OPEN} метод атомарно захватывает {@link #probeInProgress}. Поэтому метод
     * обязан вызываться <b>ровно один раз</b> на один цикл исполнения правила для одного span'а.
     * Повторный вызов в том же цикле повторно захватил бы probe-семафор и привёл бы к зависанию
     * пробного состояния (probe никогда не освободился бы корректно). Для чтения состояния без
     * побочных эффектов используйте {@link #getState()} / {@link #isOpen()}.
     *
     * @return {@link State#CLOSED} (норма), {@link State#OPEN} (правило отключено) или
     *         {@link State#HALF_OPEN} (текущему потоку выдан probe)
     */
    public State acquireExecutionState() {
        State current = state.get();
        if (current == State.OPEN
                && System.currentTimeMillis() - openedAtMs.get() >= cooldownMs) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
            current = state.get();
        }
        if (current == State.HALF_OPEN) {
            if (probeInProgress.compareAndSet(0, 1)) {
                return State.HALF_OPEN;
            }
            return State.OPEN; // другой поток уже выполняет probe — ведём себя как OPEN
        }
        return current;
    }

    /** Фиксирует успешное исполнение. В HALF_OPEN — закрывает breaker (возврат в CLOSED). */
    public void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            failureCount.set(0);
            state.set(State.CLOSED);
            probeInProgress.set(0);
            log.info("[scrubbing] Circuit breaker ЗАКРЫТ (CLOSED) для правила '{}'", ruleName);
        }
    }

    /** Фиксирует сбой. Инкрементит счётчики и при необходимости переводит в OPEN. */
    public void recordFailure(Throwable t) {
        totalFailures.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            // Probe упал — возвращаемся в OPEN и перезапускаем cooldown.
            openedAtMs.set(System.currentTimeMillis());
            state.set(State.OPEN);
            probeInProgress.set(0);
            throttledWarn("Circuit breaker probe УПАЛ для правила '{}' — возврат в OPEN");
            return;
        }

        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            openedAtMs.set(System.currentTimeMillis());
            throttledWarn("Circuit breaker ОТКРЫТ (OPEN) для правила '{}' после серии сбоев");
        } else {
            throttledWarn("Зафиксирован сбой правила '{}'");
        }
    }

    private void throttledWarn(String template) {
        long now = System.currentTimeMillis();
        long last = lastLoggedMs.get();
        if (now - last >= logThrottleMs && lastLoggedMs.compareAndSet(last, now)) {
            log.warn("[scrubbing] " + template
                    + " (счётчик={} порог={}; последующие ошибки подавлены на {}мс)",
                    ruleName, failureCount.get(), failureThreshold, logThrottleMs);
        }
    }

    // -- Чистые read-only геттеры (для JMX/диагностики, без побочных эффектов) --------------------

    public State getState() {
        return state.get();
    }

    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    public long getTotalFailures() {
        return totalFailures.get();
    }

    public String getRuleName() {
        return ruleName;
    }
}
