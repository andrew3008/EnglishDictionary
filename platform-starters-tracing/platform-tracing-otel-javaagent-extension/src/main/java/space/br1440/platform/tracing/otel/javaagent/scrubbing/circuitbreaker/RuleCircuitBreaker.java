package space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class RuleCircuitBreaker {

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
    private final AtomicInteger probeInProgress = new AtomicInteger(0);

    public RuleCircuitBreaker(String ruleName) {
        this(ruleName, 5, 60_000L, 60_000L);
    }

    public RuleCircuitBreaker(String ruleName, int failureThreshold, long cooldownMs, long logThrottleMs) {
        this.ruleName = ruleName;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.logThrottleMs = logThrottleMs;
    }

    public State acquireExecutionState() {
        State current = state.get();
        if (current == State.OPEN
                && (System.currentTimeMillis() - openedAtMs.get()) >= cooldownMs) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
            current = state.get();
        }

        if (current == State.HALF_OPEN) {
            if (probeInProgress.compareAndSet(0, 1)) {
                return State.HALF_OPEN;
            }

            return State.OPEN;
        }

        return current;
    }

    public void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            failureCount.set(0);
            state.set(State.CLOSED);
            probeInProgress.set(0);
        }
    }

    public void recordFailure(Throwable t) {
        totalFailures.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            openedAtMs.set(System.currentTimeMillis());
            state.set(State.OPEN);
            probeInProgress.set(0);
            throttledWarn("Circuit breaker probe FAILED for rule '{}' — returning to OPEN");
            return;
        }

        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            openedAtMs.set(System.currentTimeMillis());
            throttledWarn("Circuit breaker OPEN for rule '{}' after consecutive failures");
        } else {
            throttledWarn("Recorded failure for rule '{}'");
        }
    }

    private void throttledWarn(String template) {
        long now = System.currentTimeMillis();
        long last = lastLoggedMs.get();
        if ((now - last) >= logThrottleMs && lastLoggedMs.compareAndSet(last, now)) {
            log.warn("[scrubbing] " + template
                    + " (count={} threshold={}; subsequent errors suppressed for {}ms)",
                    ruleName, failureCount.get(), failureThreshold, logThrottleMs);
        }
    }

    public State getState() {
        return state.get();
    }

    public boolean isOpen() {
        return (state.get() == State.OPEN);
    }

    public long getTotalFailures() {
        return totalFailures.get();
    }
}
