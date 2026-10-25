package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class ExecutableRuleTest {

    @Test
    void stale_closed_failure_does_not_complete_in_flight_probe() throws Exception {
        // given
        CountDownLatch staleEntered = new CountDownLatch(1);
        CountDownLatch releaseStale = new CountDownLatch(1);
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        SpanAttributeScrubbingRule rule = blockingRule(
                staleEntered,
                releaseStale,
                probeEntered,
                releaseProbe
        );
        RuleCircuitBreaker breaker = new RuleCircuitBreaker("r", 1, 0L, 0L);
        ExecutableRule executable = new ExecutableRule(rule, breaker);

        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<RuleOutcome> stale = executor.submit(() -> executable.executeDetailed("stale", "value"));
                assertThat(staleEntered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(executable.executeDetailed("open", "value").outcome())
                        .isEqualTo(RuleExecutionOutcome.NON_CRITICAL_FAILURE);

                Future<RuleOutcome> probe = executor.submit(() -> executable.executeDetailed("probe", "value"));
                assertThat(probeEntered.await(10, TimeUnit.SECONDS)).isTrue();
                releaseStale.countDown();
                assertThat(stale.get(10, TimeUnit.SECONDS).outcome())
                        .isEqualTo(RuleExecutionOutcome.NON_CRITICAL_FAILURE);

                // then
                assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.HALF_OPEN);
                releaseProbe.countDown();
                assertThat(probe.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(RuleExecutionOutcome.KEEP);
                assertThat(breaker.getState()).isEqualTo(RuleCircuitBreaker.State.CLOSED);
            } finally {
                releaseStale.countDown();
                releaseProbe.countDown();
            }
        }
    }

    private static SpanAttributeScrubbingRule blockingRule(CountDownLatch staleEntered,
                                                            CountDownLatch releaseStale,
                                                            CountDownLatch probeEntered,
                                                            CountDownLatch releaseProbe) {
        return new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "r";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                if ("stale".equals(key)) {
                    staleEntered.countDown();
                    await(releaseStale);
                    throw new IllegalStateException("Stale execution failed");
                }

                if ("open".equals(key)) {
                    throw new IllegalStateException("Opening execution failed");
                }

                probeEntered.countDown();
                await(releaseProbe);
                return ScrubbingDecision.keep();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while awaiting test gate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting test gate", e);
        }
    }
}
