package space.br1440.platform.tracing.otel.javaagent.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Автомат переиспользуемого {@link CircuitBreaker}: открытие по порогу, единственный HALF_OPEN-probe,
 * закрытие после успеха, повторное открытие после провала probe.
 */
class CircuitBreakerTest {

    @Test
    void открывается_после_порога_сбоев() {
        CircuitBreaker breaker = new CircuitBreaker("c", 3, 60_000L);
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.isOpen()).isFalse();
        breaker.recordFailure();
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.getTotalFailures()).isEqualTo(3);
    }

    @Test
    void half_open_только_одному_потоку() {
        CircuitBreaker breaker = new CircuitBreaker("c", 1, 0L);
        breaker.recordFailure();
        assertThat(breaker.acquireExecutionState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.acquireExecutionState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void успешный_probe_закрывает() {
        CircuitBreaker breaker = new CircuitBreaker("c", 1, 0L);
        breaker.recordFailure();
        assertThat(breaker.acquireExecutionState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        breaker.recordSuccess();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void упавший_probe_возвращает_в_open() {
        CircuitBreaker breaker = new CircuitBreaker("c", 1, 0L);
        breaker.recordFailure();
        assertThat(breaker.acquireExecutionState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        breaker.recordFailure();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
