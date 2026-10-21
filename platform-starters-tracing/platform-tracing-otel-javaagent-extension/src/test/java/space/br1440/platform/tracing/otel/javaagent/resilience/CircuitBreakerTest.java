package space.br1440.platform.tracing.otel.javaagent.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    private final AtomicLong nanoTime = new AtomicLong();

    @Test
    void opens_after_consecutive_failure_threshold() {
        // given
        CircuitBreaker breaker = breaker(3);
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // when
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);

        // then
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isEqualTo(CircuitBreaker.Admission.REJECTED);
    }

    @Test
    void success_in_closed_resets_consecutive_failures() {
        // given
        CircuitBreaker breaker = breaker(3);
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);

        // when
        breaker.recordSuccess(CircuitBreaker.Admission.CLOSED);
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);

        // then
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isEqualTo(CircuitBreaker.Admission.CLOSED);
    }

    @Test
    void open_rejects_until_cooldown_elapses_then_admits_single_probe() {
        // given
        CircuitBreaker breaker = openBreaker();
        nanoTime.addAndGet(COOLDOWN.toNanos() - 1);
        assertThat(breaker.tryAcquire()).isEqualTo(CircuitBreaker.Admission.REJECTED);

        // when
        nanoTime.incrementAndGet();
        CircuitBreaker.Admission first = breaker.tryAcquire();
        CircuitBreaker.Admission second = breaker.tryAcquire();

        // then
        assertThat(first).isEqualTo(CircuitBreaker.Admission.PROBE);
        assertThat(second).isEqualTo(CircuitBreaker.Admission.REJECTED);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void successful_probe_closes() {
        // given
        CircuitBreaker breaker = openBreaker();
        nanoTime.addAndGet(COOLDOWN.toNanos());
        CircuitBreaker.Admission probe = breaker.tryAcquire();

        // when
        breaker.recordSuccess(probe);

        // then
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isEqualTo(CircuitBreaker.Admission.CLOSED);
    }

    @Test
    void failed_probe_reopens_and_restarts_cooldown() {
        // given
        CircuitBreaker breaker = openBreaker();
        nanoTime.addAndGet(COOLDOWN.toNanos());
        CircuitBreaker.Admission probe = breaker.tryAcquire();
        nanoTime.addAndGet(10);

        // when
        breaker.recordFailure(probe);

        // then
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        nanoTime.addAndGet(COOLDOWN.toNanos() - 1);
        assertThat(breaker.tryAcquire()).isEqualTo(CircuitBreaker.Admission.REJECTED);
        nanoTime.incrementAndGet();
        assertThat(breaker.tryAcquire()).isEqualTo(CircuitBreaker.Admission.PROBE);
    }

    @Test
    void late_closed_outcomes_do_not_change_open_or_half_open() {
        // given
        CircuitBreaker breaker = openBreaker();
        nanoTime.addAndGet(10);

        // when
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);
        nanoTime.addAndGet(COOLDOWN.toNanos() - 10);
        CircuitBreaker.Admission probe = breaker.tryAcquire();
        breaker.recordSuccess(CircuitBreaker.Admission.CLOSED);
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);

        // then
        assertThat(probe).isEqualTo(CircuitBreaker.Admission.PROBE);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isEqualTo(CircuitBreaker.Admission.REJECTED);
    }

    @Test
    void probe_outcome_outside_half_open_is_rejected() {
        // given
        CircuitBreaker breaker = breaker(1);

        // when/then
        assertThatIllegalStateException()
                .isThrownBy(() -> breaker.recordSuccess(CircuitBreaker.Admission.PROBE))
                .withMessage("Probe outcome recorded outside HALF_OPEN state: CLOSED");
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void rejected_admission_has_no_outcome() {
        // given
        CircuitBreaker breaker = breaker(1);

        // when/then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> breaker.recordFailure(CircuitBreaker.Admission.REJECTED))
                .withMessage("Rejected admission has no outcome to record");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> breaker.recordSuccess(CircuitBreaker.Admission.REJECTED))
                .withMessage("Rejected admission has no outcome to record");
    }

    @Test
    void cooldown_is_measured_across_nano_time_overflow() {
        // given
        nanoTime.set(Long.MAX_VALUE - 5);
        CircuitBreaker breaker = openBreaker();

        // when
        nanoTime.addAndGet(COOLDOWN.toNanos() - 1);
        CircuitBreaker.Admission beforeCooldown = breaker.tryAcquire();
        nanoTime.incrementAndGet();
        CircuitBreaker.Admission afterCooldown = breaker.tryAcquire();

        // then
        assertThat(beforeCooldown).isEqualTo(CircuitBreaker.Admission.REJECTED);
        assertThat(afterCooldown).isEqualTo(CircuitBreaker.Admission.PROBE);
    }

    @Test
    void invalid_configuration_is_rejected() {
        // given
        // when/then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CircuitBreaker(0, COOLDOWN))
                .withMessage("failureThreshold must be positive: 0");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CircuitBreaker(1, Duration.ZERO))
                .withMessage("cooldown must be positive: PT0S");
    }

    @Test
    void concurrent_acquire_after_cooldown_admits_exactly_one_probe() throws Exception {
        // given
        CircuitBreaker breaker = openBreaker();
        nanoTime.addAndGet(COOLDOWN.toNanos());
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CircuitBreaker.Admission>> admissions = new ArrayList<>();

        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                admissions.add(executor.submit(() -> {
                    start.await();
                    return breaker.tryAcquire();
                }));
            }
            start.countDown();

            // then
            int probes = 0;
            for (Future<CircuitBreaker.Admission> admission : admissions) {
                CircuitBreaker.Admission result = admission.get(10, TimeUnit.SECONDS);
                assertThat(result).isIn(CircuitBreaker.Admission.PROBE, CircuitBreaker.Admission.REJECTED);
                if (result == CircuitBreaker.Admission.PROBE) {
                    probes++;
                }
            }
            assertThat(probes).isEqualTo(1);
        }
    }

    private CircuitBreaker breaker(int failureThreshold) {
        return new CircuitBreaker(failureThreshold, COOLDOWN, nanoTime::get);
    }

    private CircuitBreaker openBreaker() {
        CircuitBreaker breaker = breaker(1);
        breaker.recordFailure(CircuitBreaker.Admission.CLOSED);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        return breaker;
    }
}
