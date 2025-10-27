package space.br1440.platform.leaderelection.lock.persistence.redis.resilience;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
@ToString
public class CircuitBreaker implements CircuitBreakerAdapter {

    public static final int MIN_FAILURE_THRESHOLD = 1;
    public static final int MAX_FAILURE_THRESHOLD = 100;
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;
    public static final Duration MIN_TIMEOUT = Duration.ofMillis(100);
    public static final Duration MAX_TIMEOUT = Duration.ofHours(1);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    private final int failureThreshold;
    private final Duration timeout;
    private final Clock clock;

    private CircuitBreaker(Builder builder) {
        this.failureThreshold = builder.failureThreshold;
        this.timeout = builder.timeout;
        this.clock = builder.clock;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean allowRequest() {
        CircuitBreakerState currentState = state.get();
        return switch (currentState) {
            case CLOSED, HALF_OPEN -> true;
            case OPEN -> checkAndTransitionFromOpen();
        };
    }

    @Override
    public void recordSuccess() {
        failureCount.set(0);
        state.set(CircuitBreakerState.CLOSED);
    }

    @Override
    public void recordFailure() {
        lastFailureTime.set(clock.millis());

        int currentFailures = failureCount.incrementAndGet();
        CircuitBreakerState currentState = state.get();
        
        if (currentFailures == 1) {
            log.warn("[CircuitBreaker] First failure detected. Circuit will open after {} consecutive failures.", failureThreshold);
        } else if (currentFailures >= failureThreshold && currentState == CircuitBreakerState.CLOSED) {
            log.error("[CircuitBreaker] Circuit Breaker OPEN after {} failures. " +
                      "Requests will be rejected for {}.", currentFailures, timeout);
        }

        if (currentState == CircuitBreakerState.HALF_OPEN) {
            state.set(CircuitBreakerState.OPEN);
        } else if (currentFailures >= failureThreshold) {
            state.set(CircuitBreakerState.OPEN);
        }
    }

    @Override
    public void recordFailureWithMetrics(Exception e) {
        recordFailure();
    }

    @Override
    public <T> T execute(Supplier<T> operation, Supplier<T> fallback) {
        if (allowRequest()) {
            try {
                T result = operation.get();
                recordSuccess();
                return result;
            } catch (Exception e) {
                recordFailure();
                return fallback.get();
            }
        } else {
            return fallback.get();
        }
    }

    private boolean checkAndTransitionFromOpen() {
        long now = clock.millis();
        long lastFailure = lastFailureTime.get();
        if (now - lastFailure > timeout.toMillis()) {
            return state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN);
        }

        return false;
    }

    public CircuitBreakerState getState() {
        return state.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public static class Builder {
        private int failureThreshold = DEFAULT_FAILURE_THRESHOLD;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Clock clock = Clock.systemUTC();

        public Builder failureThreshold(int failureThreshold) {
            if (failureThreshold < MIN_FAILURE_THRESHOLD || failureThreshold > MAX_FAILURE_THRESHOLD) {
                String message = String.format(
                        "Failure threshold must be between %d and %d, got: %d",
                        MIN_FAILURE_THRESHOLD, MAX_FAILURE_THRESHOLD, failureThreshold
                );
                throw new IllegalArgumentException(message);
            }

            this.failureThreshold = failureThreshold;
            return this;
        }

        public Builder timeout(Duration timeout) {
            if (timeout == null) {
                throw new IllegalArgumentException("Timeout cannot be null");
            }

            if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
                String message = String.format(
                        "Timeout must be between %s and %s, got: %s",
                        MIN_TIMEOUT, MAX_TIMEOUT, timeout
                );
                throw new IllegalArgumentException(message);
            }

            this.timeout = timeout;
            return this;
        }

        public Builder clock(Clock clock) {
            if (clock == null) {
                throw new IllegalArgumentException("Clock cannot be null");
            }

            this.clock = clock;
            return this;
        }

        public CircuitBreaker build() {
            return new CircuitBreaker(this);
        }
    }

}
