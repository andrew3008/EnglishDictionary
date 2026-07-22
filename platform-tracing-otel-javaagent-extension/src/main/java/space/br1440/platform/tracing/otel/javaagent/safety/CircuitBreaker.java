package space.br1440.platform.tracing.otel.javaagent.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Переиспользуемый circuit breaker с автоматом CLOSED → OPEN → HALF_OPEN (Фаза 11).
 * <p>
 * Обобщение проверенного {@code scrubbing.circuitbreaker.RuleCircuitBreaker} в общий строительный
 * блок safety-слоя: защищает hot-path от «log storm» и лишней работы, когда защищаемый компонент
 * стабильно падает. После {@code failureThreshold} ошибок подряд компонент временно отключается
 * (OPEN); по истечении {@code cooldownMs} допускается ровно один пробный вызов (HALF_OPEN).
 *
 * <h2>Переходы состояний</h2>
 * <pre>
 *  CLOSED ──(N ошибок подряд)──▶ OPEN ──(прошёл cooldownMs)──▶ HALF_OPEN
 *    ▲                                                              │
 *    └──────────────(probe успешен)─────────────────────────────────┘
 *    OPEN  ◀──(probe упал)───────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Конкурентность HALF_OPEN</h2>
 * Ровно один поток получает право на probe (атомарный CAS-семафор {@link #probeInProgress});
 * остальные видят OPEN до разрешения probe.
 *
 * <p><b>Потокобезопасность:</b> все поля атомарны; экземпляр безопасен для конкурентного hot-path.
 */
public final class CircuitBreaker {

    /** Состояние автомата. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String name;
    private final int failureThreshold;
    private final long cooldownMs;
    private final RateLimitedLogger rateLimitedLog;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicInteger probeInProgress = new AtomicInteger(0);

    /** Дефолты: порог 5 ошибок, cooldown 60с. */
    public CircuitBreaker(String name) {
        this(name, 5, 60_000L);
    }

    public CircuitBreaker(String name, int failureThreshold, long cooldownMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.rateLimitedLog = new RateLimitedLogger(log);
    }

    /**
     * Возвращает состояние, в котором текущий поток должен исполнять защищаемую операцию, и при
     * необходимости захватывает право на HALF_OPEN-probe.
     * <p>
     * <b>Это команда, а не запрос (осознанное нарушение CQS):</b> при возврате {@link State#HALF_OPEN}
     * метод атомарно захватывает probe-семафор. Вызывать ровно один раз на цикл исполнения.
     * Для чтения без побочных эффектов — {@link #getState()} / {@link #isOpen()}.
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
            return State.OPEN; // probe уже выполняет другой поток — ведём себя как OPEN
        }
        return current;
    }

    /** Фиксирует успех. В HALF_OPEN — закрывает breaker (возврат в CLOSED). */
    public void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            failureCount.set(0);
            state.set(State.CLOSED);
            probeInProgress.set(0);
            log.info("Circuit breaker ЗАКРЫТ (CLOSED): '{}'", name);
        }
    }

    /** Фиксирует сбой; при необходимости открывает breaker. */
    public void recordFailure() {
        totalFailures.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            openedAtMs.set(System.currentTimeMillis());
            state.set(State.OPEN);
            probeInProgress.set(0);
            rateLimitedLog.warn("Circuit breaker probe УПАЛ: '{}' — возврат в OPEN", name);
            return;
        }

        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            openedAtMs.set(System.currentTimeMillis());
            rateLimitedLog.warn("Circuit breaker ОТКРЫТ (OPEN): '{}' после {} сбоев подряд", name, failures);
        }
    }

    public State getState() {
        return state.get();
    }

    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    public long getTotalFailures() {
        return totalFailures.get();
    }

    public String getName() {
        return name;
    }
}
