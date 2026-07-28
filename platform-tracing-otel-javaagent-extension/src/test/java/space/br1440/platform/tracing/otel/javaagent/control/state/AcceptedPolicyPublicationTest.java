package space.br1440.platform.tracing.otel.javaagent.control.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class AcceptedPolicyPublicationTest {

    @Test
    void readersObserveCompleteImmutablePublishedState() throws Exception {
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(
                AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                sequencePolicy(0L));
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong violations = new AtomicLong();

        try (ExecutorService executor = Executors.newFixedThreadPool(7)) {
            List<Future<?>> readers = new ArrayList<>();
            for (int reader = 0; reader < 6; reader++) {
                readers.add(executor.submit(() -> {
                    start.await();
                    for (int observation = 0; observation < 100_000; observation++) {
                        AcceptedPolicyEnvelope accepted = store.current();
                        String expectedRoute = "/" + accepted.acceptedSequence();
                        if (!accepted.policy().samplingRouteRatios().containsKey(expectedRoute)) {
                            violations.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }

            Future<?> writer = executor.submit(() -> {
                start.await();
                for (long sequence = 1L; sequence <= 20_000L; sequence++) {
                    ExpectedPolicyBase expected = store.current().base();
                    PolicyCommitResult result = store.commit(expected, sequencePolicy(sequence));
                    if (result.outcome() != PolicyCommitOutcome.APPLIED) {
                        violations.incrementAndGet();
                    }
                }
                return null;
            });

            start.countDown();
            writer.get();
            for (Future<?> reader : readers) {
                reader.get();
            }
        }

        assertThat(violations.get()).isZero();
        assertThat(store.current().acceptedSequence()).isEqualTo(20_000L);
    }

    private static EffectiveTracingPolicy sequencePolicy(long sequence) {
        return EffectiveTracingPolicy.of(
                true,
                0.5d,
                Map.of("/" + sequence, 0.5d),
                true,
                false,
                true,
                true,
                PolicyLogLevel.INFO);
    }
}
