package space.br1440.platform.leaderelection.lock.persistence.redis.resilience;

import java.util.function.Supplier;

public interface CircuitBreakerAdapter {

    boolean allowRequest();

    void recordSuccess();

    void recordFailure();

    void recordFailureWithMetrics(Exception e);

    <T> T execute(Supplier<T> operation, Supplier<T> fallback);

}
