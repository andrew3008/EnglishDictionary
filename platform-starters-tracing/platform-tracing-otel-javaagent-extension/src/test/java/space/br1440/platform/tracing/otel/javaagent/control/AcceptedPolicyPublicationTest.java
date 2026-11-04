package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class AcceptedPolicyPublicationTest {

    @RegisterExtension
    final AgentLogCapture settingsLogs = new AgentLogCapture();

    @Test
    @SuppressWarnings("resource")
    void readersObserveCompleteImmutablePublishedState() throws Exception {
        // given
        settingsLogs.applyLevel(AgentLogLevel.OFF);
        TracingSettingsUpdater updater = AcceptedPolicyTestFixtures.bootstrapUpdater(
                AcceptedPolicyTestFixtures.AGENT_INSTANCE_ID,
                sequencePolicy(0L)
        );
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong violations = new AtomicLong();

        ExecutorService executor = Executors.newFixedThreadPool(7);
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int reader = 0; reader < 6; reader++) {
                readers.add(executor.submit(() -> {
                    assertThat(start.await(10L, TimeUnit.SECONDS)).isTrue();
                    for (int observation = 0; observation < 100_000; observation++) {
                        TracingSettingsRevision accepted = updater.current();
                        String expectedRoute = "/" + accepted.acceptedSequence();
                        boolean even = accepted.acceptedSequence() % 2L == 0L;
                        SettingsSourceAuthority authority = accepted.acceptedSequence() == 0L
                                ? SettingsSourceAuthority.BOOTSTRAP : SettingsSourceAuthority.CONFIG_SERVER;
                        if (!accepted.agentInstanceId().equals(AcceptedPolicyTestFixtures.AGENT_INSTANCE_ID)
                                || accepted.sourceAuthority() != authority
                                || accepted.samplingEnabled() != even
                                || accepted.exporterEnabled() != even
                                || accepted.traceControlHeadersEnabled() == even
                                || accepted.samplingRatio() != (even ? 0.5d : 0.25d)
                                || !accepted.samplingRouteRatios().equals(Map.of(expectedRoute, even ? 0.5d : 0.25d))) {
                            violations.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }

            Future<?> writer = executor.submit(() -> {
                assertThat(start.await(10L, TimeUnit.SECONDS)).isTrue();
                for (long sequence = 1L; sequence <= 20_000L; sequence++) {
                    SettingsRevision expected = updater.current().base();
                    SettingsUpdateResult result = updater.apply(
                            AcceptedPolicyTestFixtures.attempt(sequence),
                            expected,
                            sequencePolicy(sequence)
                    );

                    if (result.outcome() != SettingsUpdateStatus.APPLIED) {
                        violations.incrementAndGet();
                    }
                }

                return null;
            });

            // when
            start.countDown();
            writer.get(30L, TimeUnit.SECONDS);
            for (Future<?> reader : readers) {
                reader.get(30L, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10L, TimeUnit.SECONDS)).isTrue();
        }

        // then
        assertThat(violations.get()).isZero();
        assertThat(updater.current().acceptedSequence()).isEqualTo(20_000L);
    }

    private static TracingSettings sequencePolicy(long sequence) {
        boolean even = sequence % 2L == 0L;
        return TracingSettings.of(
                even,
                even ? 0.5d : 0.25d,
                Map.of("/" + sequence, even ? 0.5d : 0.25d),
                SpanAttributeValidationLevel.WARNING,
                even,
                !even,
                AgentLogLevel.INFO
        );
    }
}
