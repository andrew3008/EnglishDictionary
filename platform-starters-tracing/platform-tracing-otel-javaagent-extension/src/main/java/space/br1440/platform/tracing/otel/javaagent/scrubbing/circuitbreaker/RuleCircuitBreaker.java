package space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.MinIntervalRateLimiter;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;

public final class RuleCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(RuleCircuitBreaker.class);

    private final String ruleName;
    private final int failureThreshold;
    private final long cooldownMs;
    private final MinIntervalRateLimiter logThrottle;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicInteger probeInProgress = new AtomicInteger(0);

    public RuleCircuitBreaker(String ruleName) {
        this(ruleName, 5, 60_000L, 60_000L);
    }

    public RuleCircuitBreaker(String ruleName, int failureThreshold, long cooldownMs, long logThrottleMs) {
        this(ruleName, failureThreshold, cooldownMs, logThrottleMs, System::nanoTime);
    }

    RuleCircuitBreaker(String ruleName,
                       int failureThreshold,
                       long cooldownMs,
                       long logThrottleMs,
                       LongSupplier nanoTime) {
        this.ruleName = ruleName;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        LongSupplier monotonicClock = Objects.requireNonNull(nanoTime, "nanoTime");
        this.logThrottle = logThrottleMs > 0L
                ? new MinIntervalRateLimiter(TimeUnit.MILLISECONDS.toNanos(logThrottleMs), monotonicClock)
                : null;
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

    public void recordFailure(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        totalFailures.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            openedAtMs.set(System.currentTimeMillis());
            state.set(State.OPEN);
            probeInProgress.set(0);
            throttledWarn(ScrubbingLogEvent.SCRUBBING_CIRCUIT_PROBE_FAILED, throwable);
            return;
        }

        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            openedAtMs.set(System.currentTimeMillis());
            logWarn(ScrubbingLogEvent.SCRUBBING_CIRCUIT_OPENED, throwable);
        } else {
            throttledWarn(ScrubbingLogEvent.SCRUBBING_RULE_FAILURE_RECORDED, throwable);
        }
    }

    private void throttledWarn(AgentLogEventType eventType, Throwable throwable) {
        if (logThrottle == null || logThrottle.tryAcquire()) {
            logWarn(eventType, throwable);
        }
    }

    private void logWarn(AgentLogEventType eventType, Throwable throwable) {
        log.log(eventType, fields -> fields
                .putString(AgentLogField.NAME, ruleName)
                .putLong(AgentLogField.COUNT, failureCount.get())
                .putString(AgentLogField.FAILURE_TYPE, throwable.getClass().getName())
                .cause(throwable));
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
