package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class TracingSettingsUpdaterConcurrencyTest {

    private static final int ROUND_COUNT = 100;
    private static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.parse("00000000-0000-0000-0000-000000000042");

    @Test
    void concurrentApplyHasOneWinnerAndNoUnchangedAfterApplied() throws Exception {
        // given
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < ROUND_COUNT; round++) {
                long ordinalBase = (long) round * 3L;
                TracingSettings baseline = policy(0.10d);
                TracingSettingsStore store =
                        AcceptedPolicyTestFixtures.bootstrapAgentDefaultsStore(AGENT_INSTANCE_ID, baseline);
                TracingSettingsUpdater updater = new TracingSettingsUpdater(store, level -> { });
                SettingsUpdateResult initial = updater.apply(
                        attempt(ordinalBase + 1L),
                        store.current().base(),
                        baseline
                );
                assertThat(initial.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
                assertThat(store.current().sourceAuthority()).isEqualTo(SettingsSourceAuthority.CONFIG_SERVER);

                SettingsRevision expectedBase = initial.accepted().base();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Callable<SettingsUpdateResult> unchangedCall =
                        () -> applyAfterBarrier(
                                updater,
                                barrier,
                                attempt(ordinalBase + 2L),
                                expectedBase,
                                baseline
                        );
                Callable<SettingsUpdateResult> appliedCall =
                        () -> applyAfterBarrier(
                                updater,
                                barrier,
                                attempt(ordinalBase + 3L),
                                expectedBase,
                                policy(0.91d)
                        );

                // when
                Future<SettingsUpdateResult> unchangedFuture = executor.submit(unchangedCall);
                Future<SettingsUpdateResult> appliedFuture = executor.submit(appliedCall);
                SettingsUpdateResult unchangedCandidate = unchangedFuture.get();
                SettingsUpdateResult appliedCandidate = appliedFuture.get();
                List<SettingsUpdateResult> results = List.of(unchangedCandidate, appliedCandidate);

                // then
                assertThat(results)
                        .filteredOn(result -> result.outcome() == SettingsUpdateStatus.APPLIED)
                        .hasSize(1);
                assertThat(results)
                        .filteredOn(result -> result.outcome() == SettingsUpdateStatus.UNCHANGED
                                || result.outcome() == SettingsUpdateStatus.STALE)
                        .hasSize(1);
                assertThat(results)
                        .filteredOn(result -> result.outcome() == SettingsUpdateStatus.UNCHANGED)
                        .allMatch(result -> result.accepted().acceptedSequence() == expectedBase.acceptedSequence());
                assertThat(store.current().acceptedSequence()).isEqualTo(expectedBase.acceptedSequence() + 1L);
            }
        }
    }

    @Test
    void concurrentChangedCandidatesHaveExactlyOneAppliedWinner() throws Exception {
        // given
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < ROUND_COUNT; round++) {
                long ordinalBase = (long) round * 3L;
                TracingSettings baseline = policy(0.10d);
                TracingSettingsStore store =
                        AcceptedPolicyTestFixtures.bootstrapAgentDefaultsStore(AGENT_INSTANCE_ID, baseline);
                TracingSettingsUpdater updater = new TracingSettingsUpdater(store, level -> { });
                SettingsUpdateResult initial = updater.apply(
                        attempt(ordinalBase + 1L),
                        store.current().base(),
                        baseline
                );
                assertThat(initial.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);

                SettingsRevision expectedBase = initial.accepted().base();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Callable<SettingsUpdateResult> firstChangedCall =
                        () -> applyAfterBarrier(
                                updater,
                                barrier,
                                attempt(ordinalBase + 2L),
                                expectedBase,
                                policy(0.82d)
                        );
                Callable<SettingsUpdateResult> secondChangedCall =
                        () -> applyAfterBarrier(
                                updater,
                                barrier,
                                attempt(ordinalBase + 3L),
                                expectedBase,
                                policy(0.91d)
                        );

                // when
                Future<SettingsUpdateResult> firstFuture = executor.submit(firstChangedCall);
                Future<SettingsUpdateResult> secondFuture = executor.submit(secondChangedCall);
                List<SettingsUpdateResult> results = List.of(firstFuture.get(), secondFuture.get());

                // then
                assertThat(results)
                        .filteredOn(result -> result.outcome() == SettingsUpdateStatus.APPLIED)
                        .hasSize(1);
                assertThat(results)
                        .filteredOn(result -> result.outcome() == SettingsUpdateStatus.STALE)
                        .hasSize(1);
                assertThat(store.current().acceptedSequence()).isEqualTo(expectedBase.acceptedSequence() + 1L);
            }
        }
    }

    private static SettingsUpdateResult applyAfterBarrier(TracingSettingsUpdater updater,
                                                           CyclicBarrier barrier,
                                                           SettingsUpdateAttempt attempt,
                                                           SettingsRevision expectedBase,
                                                           TracingSettings candidate) throws Exception {
        barrier.await();
        return updater.apply(attempt, expectedBase, candidate);
    }

    private static SettingsUpdateAttempt attempt(long ordinal) {
        return new SettingsUpdateAttempt(
                new UUID(0L, ordinal).toString(),
                SettingsWriter.CONFIG_SERVER
        );
    }

    private static TracingSettings policy(double samplingRatio) {
        return TracingSettings.of(
                true,
                samplingRatio,
                Map.of(),
                SpanAttributeValidationLevel.WARNING,
                true,
                true,
                AgentLogLevel.INFO
        );
    }
}
