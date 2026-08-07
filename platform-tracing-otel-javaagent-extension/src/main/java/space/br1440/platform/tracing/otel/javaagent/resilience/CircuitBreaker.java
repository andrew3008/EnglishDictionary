package space.br1440.platform.tracing.otel.javaagent.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long cooldownMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicInteger probeInProgress = new AtomicInteger(0);

    public CircuitBreaker(String name) {
        this(name, 5, 60_000L);
    }

    public CircuitBreaker(String name, int failureThreshold, long cooldownMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    public State acquireExecutionState() {
        State current = state.get();
        if ((current == State.OPEN) && (System.currentTimeMillis() - openedAtMs.get() >= cooldownMs)) {
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

    public void recordFailure() {
        totalFailures.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            openedAtMs.set(System.currentTimeMillis());
            state.set(State.OPEN);
            probeInProgress.set(0);
            return;
        }

        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            openedAtMs.set(System.currentTimeMillis());
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
