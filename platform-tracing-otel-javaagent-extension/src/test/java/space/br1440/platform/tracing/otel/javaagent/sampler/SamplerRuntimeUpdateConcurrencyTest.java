package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyUpdateOutcome;

/**
 * Проверяет one-shot expected-base семантику runtime sampling updates.
 */
class SamplerRuntimeUpdateConcurrencyTest {

    @Test
    void acceptedSequenceEqualsSuccessfulCommitsWithoutHiddenRetry() throws Exception {
        AcceptedPolicyAccess policy =
                AcceptedPolicyAccess.isolatedSampling(true, List.of(), List.of("on"), Map.of(), 0.5d);
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AcceptedPolicyUpdateOutcome>> results = new ArrayList<>(writers);

        try {
            for (int index = 0; index < writers; index++) {
                double ratio = (index + 1.0d) / (writers + 1.0d);
                results.add(pool.submit(() -> {
                    start.await();
                    return policy.updateSampling(true, ratio, Map.of());
                }));
            }
            start.countDown();

            long applied = 0L;
            long stale = 0L;
            for (Future<AcceptedPolicyUpdateOutcome> result : results) {
                AcceptedPolicyUpdateOutcome outcome = result.get(10, TimeUnit.SECONDS);
                if (outcome == AcceptedPolicyUpdateOutcome.APPLIED) {
                    applied++;
                } else if (outcome == AcceptedPolicyUpdateOutcome.STALE) {
                    stale++;
                }
            }

            assertThat(applied).isPositive();
            assertThat(applied + stale).isEqualTo(writers);
            assertThat(policy.current().acceptedSequence()).isEqualTo(applied);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidCandidateKeepsLogicalLastKnownGood() {
        AcceptedPolicyAccess policy =
                AcceptedPolicyAccess.isolatedSampling(true, List.of(), List.of("on"), Map.of(), 0.5d);
        var before = policy.current();

        AcceptedPolicyUpdateOutcome outcome =
                policy.updateSampling(true, 5.0d, Map.of());

        assertThat(outcome).isEqualTo(AcceptedPolicyUpdateOutcome.REJECTED);
        assertThat(policy.current()).isSameAs(before);
        assertThat(policy.current().acceptedSequence()).isZero();
    }
}
