package space.br1440.platform.tracing.otel.extension.scrubbing.circuitbreaker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты автомата circuit breaker (PR-4): открытие по порогу, конкурентность HALF_OPEN
 * (ровно один probe), закрытие после успешного probe.
 */
class RuleCircuitBreakerTest {

    @Test
    void открывается_после_порога_ошибок() {
        var breaker = new RuleCircuitBreaker("r", 3, 60_000L, 60_000L);
        breaker.recordFailure(new RuntimeException());
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.isOpen()).isFalse();
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.getTotalFailures()).isEqualTo(3);
    }

    @Test
    void half_open_выдаёт_probe_только_одному_потоку() {
        // cooldown=0 → сразу после OPEN первый вызов получает HALF_OPEN, второй — OPEN.
        var breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.OPEN);
    }

    @Test
    void успешный_probe_закрывает_breaker() {
        var breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        breaker.recordSuccess();
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.CLOSED);
    }

    @Test
    void упавший_probe_возвращает_в_open() {
        var breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.OPEN);
    }
}
