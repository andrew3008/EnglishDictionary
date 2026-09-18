package space.br1440.platform.tracing.otel.javaagent.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    @Test
    void opens_after_failure_threshold() {
        // given
        CircuitBreaker breaker = new CircuitBreaker("c", 3, 60_000L);
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.isOpen()).isFalse();
        // when
        breaker.recordFailure();
        // then
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.getTotalFailures()).isEqualTo(3);
    }

    @Test
    void half_open_allows_only_one_thread() {
        // given
        CircuitBreaker breaker = new CircuitBreaker("c", 1, 0L);
        breaker.recordFailure();
        // when
        CircuitBreaker.State first = breaker.acquireExecutionState();
        CircuitBreaker.State second = breaker.acquireExecutionState();
        // then
        assertThat(first).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(second).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void successful_probe_closes() {
        // given
        CircuitBreaker breaker = new CircuitBreaker("c", 1, 0L);
        breaker.recordFailure();
        assertThat(breaker.acquireExecutionState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        // when
        breaker.recordSuccess();
        // then
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failed_probe_returns_to_open() {
        // given
        CircuitBreaker breaker = new CircuitBreaker("c", 1, 0L);
        breaker.recordFailure();
        assertThat(breaker.acquireExecutionState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        // when
        breaker.recordFailure();
        // then
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
