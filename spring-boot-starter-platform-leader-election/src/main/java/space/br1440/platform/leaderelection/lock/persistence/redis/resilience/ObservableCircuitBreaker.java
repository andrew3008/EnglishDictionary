package space.br1440.platform.leaderelection.lock.persistence.redis.resilience;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;

import java.util.function.Supplier;

@Slf4j
public class ObservableCircuitBreaker implements CircuitBreakerAdapter {

    private final CircuitBreaker delegate;
    private final LeaderElectionMetricsInterface metrics;
    private final String role;
    private volatile CircuitBreakerState lastLoggedState = CircuitBreakerState.CLOSED;

    public ObservableCircuitBreaker(CircuitBreaker delegate,
                                    LeaderElectionMetricsInterface metrics,
                                    String role) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.role = role;
    }

    @Override
    public boolean allowRequest() {
        boolean allowed = delegate.allowRequest();
        CircuitBreakerState currentState = delegate.getState();

        if (currentState != lastLoggedState) {
            logStateTransition(currentState);
            lastLoggedState = currentState;
        }
        
        if (!allowed) {
            metrics.recordFailedAttempt(role, "circuit_breaker_rejected");
        }

        return allowed;
    }

    @Override
    public <T> T execute(Supplier<T> operation, Supplier<T> fallback) {
        if (allowRequest()) {
            try {
                T result = operation.get();
                delegate.recordSuccess();
                return result;
            } catch (Exception e) {
                delegate.recordFailure();
                metrics.recordFailedAttempt(role, classifyException(e));
                return fallback.get();
            }
        } else {
            return fallback.get();
        }
    }

    @Override
    public void recordSuccess() {
        delegate.recordSuccess();
    }

    @Override
    public void recordFailure() {
        delegate.recordFailure();
    }

    @Override
    public void recordFailureWithMetrics(Exception e) {
        delegate.recordFailure();

        String errorType = classifyException(e);
        metrics.recordFailedAttempt(role, errorType);
        
        logFailure(errorType, e);
    }

    private void logFailure(String errorType, Exception e) {
        CircuitBreakerState state = delegate.getState();
        int failureCount = delegate.getFailureCount();
        int threshold = delegate.getFailureThreshold();
        
        if (state == CircuitBreakerState.CLOSED && failureCount == 1) {
            log.warn("[LeaderElection:{}] Redis operation failed: {} - {}. " +
                     "Will retry. Circuit Breaker will open after {} consecutive failures.",
                     role, errorType, e.getMessage(), threshold, e);
        } else if (state == CircuitBreakerState.CLOSED && failureCount > 1) {
            log.warn("[LeaderElection:{}] Redis failure {}/{}: {} - {}",
                     role, failureCount, threshold, errorType, e.getMessage());
        } else if (state == CircuitBreakerState.OPEN) {
            log.error("[LeaderElection:{}] Circuit Breaker OPEN - Redis unavailable. " +
                      "Subsequent requests will be rejected for 30 seconds. " +
                      "Last error: {} - {}", role, errorType, e.getMessage());
        } else if (state == CircuitBreakerState.HALF_OPEN) {
            log.info("[LeaderElection:{}] Circuit Breaker HALF_OPEN - testing Redis recovery. " +
                     "Error: {} - {}", role, errorType, e.getMessage());
        }
    }

    private void logStateTransition(CircuitBreakerState newState) {
        switch (newState) {
            case OPEN -> log.error(
                "[LeaderElection:{}] Circuit Breaker OPEN. Redis unavailable. " +
                "Lock operations blocked for 30 seconds.", role
            );

            case HALF_OPEN -> log.info(
                "[LeaderElection:{}] Circuit Breaker HALF_OPEN. Testing recovery...", role
            );

            case CLOSED -> log.info(
                "[LeaderElection:{}] Circuit Breaker CLOSED. Redis restored.", role
            );
        }
    }

    private String classifyException(Exception e) {
        String simpleName = e.getClass().getSimpleName();
        if (simpleName.contains("Connection") || simpleName.contains("Connect")) {
            return "redis_connection_error";
        } else if (simpleName.contains("Timeout") || simpleName.contains("TimeOut")) {
            return "redis_timeout";
        } else if (simpleName.contains("Auth")) {
            return "redis_authentication_error";
        } else if (simpleName.contains("Redis")) {
            return "redis_unknown_error";
        } else {
            return "infrastructure_error";
        }
    }

}
