package space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

/**
 * Тесты автомата circuit breaker (PR-4): открытие по порогу, конкурентность HALF_OPEN
 * (ровно один probe), закрытие после успешного probe.
 */
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class RuleCircuitBreakerTest {

    private static final String SECRET_MARKER = "rule-failure-secret";

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void null_failure_is_rejected_without_state_change_or_log_emission() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("safe-rule", 1, 60_000L, 0L);

        // when
        NullPointerException failure = assertThrows(NullPointerException.class, () -> breaker.recordFailure(null));

        // then
        assertThat(failure).hasMessage("throwable");
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.CLOSED);
        assertThat(breaker.getTotalFailures()).isZero();
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void opens_after_error_threshold() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 3, 60_000L, 60_000L);
        // when
        breaker.recordFailure(new RuntimeException());
        breaker.recordFailure(new RuntimeException());
        // then
        assertThat(breaker.isOpen()).isFalse();
        // when
        breaker.recordFailure(new RuntimeException());
        // then
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.getTotalFailures()).isEqualTo(3);
    }

    @Test
    void half_open_issues_probe_to_only_one_thread() {
        // cooldown=0 → сразу после OPEN первый вызов получает HALF_OPEN, второй — OPEN.
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        // when
        breaker.recordFailure(new RuntimeException());
        // then
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.OPEN);
    }

    @Test
    void successful_probe_closes_breaker() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        // when
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        breaker.recordSuccess();
        // then
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.CLOSED);
    }

    @Test
    void failed_probe_returns_to_open() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        // when
        breaker.recordFailure(new RuntimeException());
        assertThat(breaker.acquireExecutionState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        breaker.recordFailure(new RuntimeException());
        // then
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.OPEN);
    }

    @Test
    void failure_log_includes_type_without_exception_message_fields() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("safe-rule", 1, 60_000L, 0L);
        IllegalStateException failure = new IllegalStateException(SECRET_MARKER);

        // when
        breaker.recordFailure(failure);

        // then
        assertThat(logs.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.SCRUB_CIRCUIT_OPEN)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.fields())
                            .hasSize(3)
                            .containsEntry("name", "safe-rule")
                            .containsEntry("count", "1")
                            .containsEntry("failureType", IllegalStateException.class.getName())
                            .doesNotContainKeys("exceptionType", "exceptionMessage");
                    assertThat(event.throwableDigest())
                            .contains(IllegalStateException.class.getName())
                            .doesNotContain(SECRET_MARKER);
                });
        assertThat(logs.rendered()).doesNotContain(SECRET_MARKER);
    }

    @Test
    void warning_throttle_uses_monotonic_interval_gate() {
        // given
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(1L);
        AtomicLong nanoTime = new AtomicLong();
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("safe-rule", 10, 60_000L, 1L, nanoTime::get);
        IllegalStateException failure = new IllegalStateException(SECRET_MARKER);

        // when
        breaker.recordFailure(failure);
        nanoTime.set(intervalNanos - 1L);
        breaker.recordFailure(failure);
        nanoTime.set(intervalNanos);
        breaker.recordFailure(failure);

        // then
        assertThat(logs.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.SCRUB_CIRCUIT_OPEN)
                .extracting(event -> event.fields().get("count"))
                .containsExactly("1", "3");
    }
}
