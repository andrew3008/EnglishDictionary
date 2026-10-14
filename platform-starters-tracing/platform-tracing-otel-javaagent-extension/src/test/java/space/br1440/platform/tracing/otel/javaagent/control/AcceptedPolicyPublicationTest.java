package space.br1440.platform.tracing.otel.javaagent.control;

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

import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitOutcome;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class AcceptedPolicyPublicationTest {

    @Test
    void readersObserveCompleteImmutablePublishedState() throws Exception {
        // given
        TracingSettingsStore store = AcceptedPolicyTestFixtures.bootstrapAgentDefaultsStore(
                AcceptedPolicyTestFixtures.AGENT_INSTANCE_ID,
                sequencePolicy(0L)
        );
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong violations = new AtomicLong();

        try (ExecutorService executor = Executors.newFixedThreadPool(7)) {
            List<Future<?>> readers = new ArrayList<>();
            for (int reader = 0; reader < 6; reader++) {
                readers.add(executor.submit(() -> {
                    start.await();
                    for (int observation = 0; observation < 100_000; observation++) {
                        TracingSettingsRevision accepted = store.current();
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
                    SettingsRevision expected = store.current().base();
                    SettingsCommitResult result = store.commit(
                            expected,
                            sequencePolicy(sequence),
                            SettingsSourceAuthority.CONFIG_SERVER
                    );

                    if (result.outcome() != SettingsCommitOutcome.APPLIED) {
                        violations.incrementAndGet();
                    }
                }

                return null;
            });

            // when
            start.countDown();
            writer.get();
            for (Future<?> reader : readers) {
                reader.get();
            }
        }

        // then
        assertThat(violations.get()).isZero();
        assertThat(store.current().acceptedSequence()).isEqualTo(20_000L);
    }

    private static TracingSettings sequencePolicy(long sequence) {
        return TracingSettings.of(
                true,
                0.5d,
                Map.of("/" + sequence, 0.5d),
                SpanAttributeValidationLevel.WARNING,
                true,
                true,
                AgentLogLevel.INFO
        );
    }
}
