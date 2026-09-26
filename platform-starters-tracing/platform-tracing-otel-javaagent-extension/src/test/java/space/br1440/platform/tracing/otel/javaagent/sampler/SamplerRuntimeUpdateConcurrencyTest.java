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

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

/**
 * Проверяет one-shot expected-base семантику runtime sampling updates.
 */
class SamplerRuntimeUpdateConcurrencyTest {

    @Test
    void acceptedSequenceEqualsSuccessfulCommitsWithoutHiddenRetry() throws Exception {
        // given
        TracingSettingsSource policy = TracingSettingsSourceTestFixtures.boundSampling(
                AgentInstanceId.random(), true, Map.of(), 0.5d
        );

        int writers = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SettingsUpdateStatus>> results = new ArrayList<>(writers);

        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            for (int index = 0; index < writers; index++) {
                double ratio = (index + 1.0d) / (writers + 1.0d);
                results.add(pool.submit(() -> {
                    start.await();
                    return AcceptedPolicyMutationAccess.applySampling(policy, true, ratio, Map.of());
                }));
            }
            start.countDown();

            // when
            long applied = 0L;
            long stale = 0L;
            for (Future<SettingsUpdateStatus> result : results) {
                SettingsUpdateStatus outcome = result.get(10, TimeUnit.SECONDS);
                if (outcome == SettingsUpdateStatus.APPLIED) {
                    applied++;
                } else if (outcome == SettingsUpdateStatus.STALE) {
                    stale++;
                }
            }

            // then
            assertThat(applied).isPositive();
            assertThat(applied + stale).isEqualTo(writers);
            assertThat(policy.current().acceptedSequence()).isEqualTo(applied);
        }
    }

    @Test
    void invalidCandidateKeepsLogicalLastKnownGood() {
        // given
        TracingSettingsSource policy = TracingSettingsSourceTestFixtures.boundSampling(
                AgentInstanceId.random(), true, Map.of(), 0.5d);
        TracingSettingsRevision before = policy.current();

        // when
        SettingsUpdateStatus outcome =
                AcceptedPolicyMutationAccess.applySampling(policy, true, 5.0d, Map.of());

        // then
        assertThat(outcome).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(policy.current()).isSameAs(before);
        assertThat(policy.current().acceptedSequence()).isZero();
    }
}
