package space.br1440.platform.leaderelection.lock.persistence.redis.resilience;

import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;

import java.time.Clock;
import java.time.Duration;

public class CircuitBreakerFactory {

    public static CircuitBreakerAdapter create(int failureThreshold, Duration timeout) {
        return CircuitBreaker.builder()
                .failureThreshold(failureThreshold)
                .timeout(timeout)
                .clock(Clock.systemUTC())
                .build();
    }

    public static ObservableCircuitBreaker createWithMetrics(int failureThreshold,
                                                             Duration timeout,
                                                             LeaderElectionMetricsInterface metrics,
                                                             String role) {
        CircuitBreaker baseCircuitBreaker = CircuitBreaker.builder()
                .failureThreshold(failureThreshold)
                .timeout(timeout)
                .clock(Clock.systemUTC())
                .build();
        return new ObservableCircuitBreaker(baseCircuitBreaker, metrics, role);
    }

}

