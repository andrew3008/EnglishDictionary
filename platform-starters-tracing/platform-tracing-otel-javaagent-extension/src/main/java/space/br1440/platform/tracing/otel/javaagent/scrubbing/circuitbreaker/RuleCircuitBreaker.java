package space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.MinIntervalRateLimiter;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public final class RuleCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public static final class Admission {

        private final State state;
        private final long generation;

        private Admission(State state, long generation) {
            this.state = state;
            this.generation = generation;
        }

        public State state() {
            return state;
        }
    }

    private record CircuitState(State state,
                                long generation,
                                long openedAtNanos,
                                int failureCount,
                                Admission admission) {
    }

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(RuleCircuitBreaker.class);
    private static final Admission REJECTED_ADMISSION = new Admission(State.OPEN, -1L);

    private final String ruleName;
    private final int failureThreshold;
    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private final MinIntervalRateLimiter logThrottle;

    private final AtomicReference<CircuitState> circuitState = new AtomicReference<>(
            newCircuitState(State.CLOSED, 0L, 0L, 0)
    );
    private final AtomicLong totalFailures = new AtomicLong(0);

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
        LongSupplier monotonicClock = Objects.requireNonNull(nanoTime, "nanoTime");
        this.cooldownNanos = TimeUnit.MILLISECONDS.toNanos(cooldownMs);
        this.nanoTime = monotonicClock;
        this.logThrottle = logThrottleMs > 0L
                ? new MinIntervalRateLimiter(TimeUnit.MILLISECONDS.toNanos(logThrottleMs), monotonicClock)
                : null;
    }

    public Admission acquireExecution() {
        while (true) {
            CircuitState current = circuitState.get();
            if (current.state() == State.CLOSED) {
                return current.admission();
            }

            if (current.state() == State.HALF_OPEN) {
                return REJECTED_ADMISSION;
            }

            if ((nanoTime.getAsLong() - current.openedAtNanos()) < cooldownNanos) {
                return REJECTED_ADMISSION;
            }

            CircuitState probing = newCircuitState(
                    State.HALF_OPEN,
                    current.generation() + 1L,
                    current.openedAtNanos(),
                    current.failureCount()
            );
            if (circuitState.compareAndSet(current, probing)) {
                return probing.admission();
            }
        }
    }

    public void recordSuccess(Admission admission) {
        Objects.requireNonNull(admission, "admission");

        if (admission.state == State.OPEN) {
            throw new IllegalArgumentException("Rejected admission has no outcome to record");
        }

        if (admission.state != State.HALF_OPEN) {
            return;
        }

        CircuitState current = circuitState.get();
        if (current.state() != State.HALF_OPEN || current.generation() != admission.generation) {
            return;
        }

        circuitState.compareAndSet(
                current,
                newCircuitState(State.CLOSED, current.generation(), current.openedAtNanos(), 0)
        );
    }

    public void recordFailure(Admission admission, Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        Objects.requireNonNull(admission, "admission");
        if (admission.state == State.OPEN) {
            throw new IllegalArgumentException("Rejected admission has no outcome to record");
        }

        totalFailures.incrementAndGet();
        if (admission.state == State.HALF_OPEN) {
            recordProbeFailure(admission, throwable);
            return;
        }

        recordClosedFailure(admission, throwable);
    }

    private void recordProbeFailure(Admission admission, Throwable throwable) {
        CircuitState current = circuitState.get();
        if (current.state() != State.HALF_OPEN || current.generation() != admission.generation) {
            return;
        }

        CircuitState opened = newCircuitState(
                State.OPEN,
                current.generation(),
                nanoTime.getAsLong(),
                current.failureCount()
        );

        if (circuitState.compareAndSet(current, opened)) {
            throttledWarn(
                    ScrubbingLogEvent.SCRUBBING_CIRCUIT_PROBE_FAILED,
                    throwable,
                    current.failureCount()
            );
        }
    }

    private void recordClosedFailure(Admission admission, Throwable throwable) {
        while (true) {
            CircuitState current = circuitState.get();
            if (current.state() != State.CLOSED || current.generation() != admission.generation) {
                return;
            }

            int failures = current.failureCount() + 1;
            State nextState = failures >= failureThreshold ? State.OPEN : State.CLOSED;
            long openedAtNanos = nextState == State.OPEN
                    ? nanoTime.getAsLong()
                    : current.openedAtNanos();
            CircuitState next = newCircuitState(
                    nextState,
                    current.generation(),
                    openedAtNanos,
                    failures
            );

            if (!circuitState.compareAndSet(current, next)) {
                continue;
            }

            if (nextState == State.OPEN) {
                logWarn(ScrubbingLogEvent.SCRUBBING_CIRCUIT_OPENED, throwable, failures);
            } else {
                throttledWarn(ScrubbingLogEvent.SCRUBBING_RULE_FAILURE_RECORDED, throwable, failures);
            }

            return;
        }
    }

    private void throttledWarn(AgentLogEventType eventType, Throwable throwable, int failureCount) {
        if (logThrottle == null || logThrottle.tryAcquire()) {
            logWarn(eventType, throwable, failureCount);
        }
    }

    private void logWarn(AgentLogEventType eventType, Throwable throwable, int failureCount) {
        log.log(eventType, fields -> fields
                .putString(AgentLogField.NAME, ruleName)
                .putLong(AgentLogField.COUNT, failureCount)
                .putString(AgentLogField.FAILURE_TYPE, throwable.getClass().getName())
                .cause(throwable));
    }

    private static CircuitState newCircuitState(State state,
                                                long generation,
                                                long openedAtNanos,
                                                int failureCount) {
        Admission admission = state == State.OPEN
                ? REJECTED_ADMISSION
                : new Admission(state, generation);
        return new CircuitState(state, generation, openedAtNanos, failureCount, admission);
    }

    public State getState() {
        return circuitState.get().state();
    }

    public boolean isOpen() {
        return circuitState.get().state() == State.OPEN;
    }

    public long getTotalFailures() {
        return totalFailures.get();
    }
}
