package space.br1440.platform.tracing.otel.javaagent.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class CircuitBreaker {

    public enum Admission { CLOSED, PROBE, REJECTED }

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private final Object monitor = new Object();

    private volatile State state = State.CLOSED;
    private volatile int consecutiveFailures;
    private long openedAtNanos;

    public CircuitBreaker(int failureThreshold, Duration cooldown) {
        this(failureThreshold, cooldown, System::nanoTime);
    }

    CircuitBreaker(int failureThreshold, Duration cooldown, LongSupplier nanoTime) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive: " + failureThreshold);
        }

        Objects.requireNonNull(cooldown, "cooldown");
        if (!cooldown.isPositive()) {
            throw new IllegalArgumentException("cooldown must be positive: " + cooldown);
        }

        this.failureThreshold = failureThreshold;
        this.cooldownNanos = cooldown.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public Admission tryAcquire() {
        if (state == State.CLOSED) {
            return Admission.CLOSED;
        }

        synchronized (monitor) {
            return switch (state) {
                case CLOSED -> Admission.CLOSED;
                case HALF_OPEN -> Admission.REJECTED;
                case OPEN -> {
                    if ((nanoTime.getAsLong() - openedAtNanos) < cooldownNanos) {
                        yield Admission.REJECTED;
                    }

                    state = State.HALF_OPEN;
                    yield Admission.PROBE;
                }
            };
        }
    }

    public void recordSuccess(Admission admission) {
        switch (admission) {
            case CLOSED -> resetConsecutiveFailures();
            case PROBE -> {
                synchronized (monitor) {
                    requireProbeInFlight();
                    state = State.CLOSED;
                }
            }
            case REJECTED -> throw rejectedOutcome();
        }
    }

    public void recordFailure(Admission admission) {
        switch (admission) {
            case CLOSED -> {
                synchronized (monitor) {
                    if (state != State.CLOSED) {
                        return;
                    }

                    int failures = consecutiveFailures + 1;
                    consecutiveFailures = failures;
                    if (failures >= failureThreshold) {
                        open();
                    }
                }
            }

            case PROBE -> {
                synchronized (monitor) {
                    requireProbeInFlight();
                    open();
                }
            }

            case REJECTED -> throw rejectedOutcome();
        }
    }

    State state() {
        return state;
    }

    private void resetConsecutiveFailures() {
        if (consecutiveFailures == 0) {
            return;
        }

        synchronized (monitor) {
            consecutiveFailures = 0;
        }
    }

    private void open() {
        openedAtNanos = nanoTime.getAsLong();
        consecutiveFailures = 0;
        state = State.OPEN;
    }

    private void requireProbeInFlight() {
        if (state != State.HALF_OPEN) {
            throw new IllegalStateException("Probe outcome recorded outside HALF_OPEN state: " + state);
        }
    }

    private static IllegalArgumentException rejectedOutcome() {
        return new IllegalArgumentException("Rejected admission has no outcome to record");
    }
}
