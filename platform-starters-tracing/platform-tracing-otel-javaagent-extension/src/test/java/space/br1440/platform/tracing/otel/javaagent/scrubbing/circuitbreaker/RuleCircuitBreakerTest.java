package space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class RuleCircuitBreakerTest {

    private static final String SECRET_MARKER = "rule-failure-secret";

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void null_failure_is_rejected_without_state_change_or_log_emission() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("safe-rule", 1, 60_000L, 0L);
        RuleCircuitBreaker.Admission admission = breaker.acquireExecution();

        // when
        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> breaker.recordFailure(admission, null)
        );

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
        breaker.recordFailure(breaker.acquireExecution(), new RuntimeException());
        breaker.recordFailure(breaker.acquireExecution(), new RuntimeException());
        // then
        assertThat(breaker.isOpen()).isFalse();
        // when
        breaker.recordFailure(breaker.acquireExecution(), new RuntimeException());
        // then
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.getTotalFailures()).isEqualTo(3);
    }

    @Test
    void half_open_issues_probe_to_only_one_thread() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        // when
        breaker.recordFailure(breaker.acquireExecution(), new RuntimeException());
        // then
        assertThat(breaker.acquireExecution().state()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.acquireExecution().state()).isEqualTo(RuleCircuitBreaker.State.OPEN);
    }

    @Test
    void successful_probe_closes_breaker() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        // when
        breaker.recordFailure(breaker.acquireExecution(), new RuntimeException());
        RuleCircuitBreaker.Admission probe = breaker.acquireExecution();
        assertThat(probe.state()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        breaker.recordSuccess(probe);
        // then
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.CLOSED);
    }

    @Test
    void failed_probe_returns_to_open() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        // when
        breaker.recordFailure(breaker.acquireExecution(), new RuntimeException());
        RuleCircuitBreaker.Admission probe = breaker.acquireExecution();
        assertThat(probe.state()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        breaker.recordFailure(probe, new RuntimeException());
        // then
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.OPEN);
    }

    @Test
    void cooldown_uses_injected_monotonic_clock_across_overflow() {
        // given
        long cooldownNanos = TimeUnit.MILLISECONDS.toNanos(10L);
        AtomicLong nanoTime = new AtomicLong(Long.MAX_VALUE - 5L);
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 1, 10L, 0L, nanoTime::get);
        breaker.recordFailure(breaker.acquireExecution(), new RuntimeException());

        // when
        nanoTime.addAndGet(cooldownNanos - 1L);
        RuleCircuitBreaker.Admission beforeCooldown = breaker.acquireExecution();
        nanoTime.incrementAndGet();
        RuleCircuitBreaker.Admission afterCooldown = breaker.acquireExecution();

        // then
        assertThat(beforeCooldown.state()).isEqualTo(RuleCircuitBreaker.State.OPEN);
        assertThat(afterCooldown.state()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void stale_outcomes_from_previous_generations_do_not_complete_current_probe() {
        // given
        long cooldownNanos = TimeUnit.MILLISECONDS.toNanos(1L);
        AtomicLong nanoTime = new AtomicLong();
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 1, 1L, 0L, nanoTime::get);
        RuleCircuitBreaker.Admission staleClosed = breaker.acquireExecution();
        breaker.recordFailure(breaker.acquireExecution(), new RuntimeException());
        nanoTime.addAndGet(cooldownNanos);
        RuleCircuitBreaker.Admission previousProbe = breaker.acquireExecution();
        breaker.recordFailure(previousProbe, new RuntimeException());
        nanoTime.addAndGet(cooldownNanos);
        RuleCircuitBreaker.Admission currentProbe = breaker.acquireExecution();

        // when
        breaker.recordFailure(staleClosed, new RuntimeException());
        breaker.recordFailure(previousProbe, new RuntimeException());
        breaker.recordSuccess(previousProbe);

        // then
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
        breaker.recordSuccess(currentProbe);
        assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.CLOSED);
    }

    @Test
    void failure_log_includes_type_without_exception_message_fields() {
        // given
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("safe-rule", 1, 60_000L, 0L);
        IllegalStateException failure = new IllegalStateException(SECRET_MARKER);

        // when
        breaker.recordFailure(breaker.acquireExecution(), failure);

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
        breaker.recordFailure(breaker.acquireExecution(), failure);
        nanoTime.set(intervalNanos - 1L);
        breaker.recordFailure(breaker.acquireExecution(), failure);
        nanoTime.set(intervalNanos);
        breaker.recordFailure(breaker.acquireExecution(), failure);

        // then
        assertThat(logs.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.SCRUB_CIRCUIT_OPEN)
                .extracting(event -> event.fields().get("count"))
                .containsExactly("1", "3");
    }
}
