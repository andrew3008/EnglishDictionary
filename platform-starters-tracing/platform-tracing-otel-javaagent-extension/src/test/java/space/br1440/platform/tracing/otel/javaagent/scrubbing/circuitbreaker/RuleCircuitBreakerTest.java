package space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты автомата circuit breaker (PR-4): открытие по порогу, конкурентность HALF_OPEN
 * (ровно один probe), закрытие после успешного probe.
 */
class RuleCircuitBreakerTest {

    @Test
    void opens_after_error_threshold() {
        var breaker = new RuleCircuitBreaker("r", 3, 60_000L, 60_000L);
        breaker.recordFailure(new RuntimeException());
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.isOpen()).isFalse();
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.getTotalFailures()).isEqualTo(3);
    }

    @Test
    void half_open_issues_probe_to_only_one_thread() {
        // cooldown=0 → сразу после OPEN первый вызов получает HALF_OPEN, второй — OPEN.
        var breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.OPEN);
    }

    @Test
    void successful_probe_closes_breaker() {
        var breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        breaker.recordSuccess();
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.CLOSED);
    }

    @Test
    void failed_probe_returns_to_open() {
        var breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.OPEN);
    }
}
