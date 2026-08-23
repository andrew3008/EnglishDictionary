package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class LastSettingsUpdateConcurrencyTest {

    private static final int THREAD_COUNT = 32;
    private static final String RUNTIME_INSTANCE_ID = "00000000-0000-0000-0000-000000000042";

    @Test
    void concurrentApply_hasExactlyOneWinner_andCoherentDiagnostics() throws Exception {
        // given: baseline policy, отличный от него candidate и общий store с revision S
        TracingSettings baseline = policy(0.10d);
        TracingSettings candidate = policy(0.91d);
        TracingSettingsStore store = new TracingSettingsStore(RUNTIME_INSTANCE_ID, baseline);
        long expectedSequence = store.current().acceptedSequence();
        ExpectedSettingsRevision expectedBase =
                new ExpectedSettingsRevision(RUNTIME_INSTANCE_ID, expectedSequence);

        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(
                store, new SettingsValidationConstraints(true), diagnostics);

        List<SettingsUpdateAttempt> attempts = new ArrayList<>(THREAD_COUNT);
        for (int index = 0; index < THREAD_COUNT; index++) {
            attempts.add(attempt(UUID.randomUUID().toString()));
        }

        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        List<Callable<SettingsUpdateResult>> calls = new ArrayList<>(THREAD_COUNT);
        for (SettingsUpdateAttempt attempt : attempts) {
            calls.add(() -> {
                barrier.await();
                return updater.apply(attempt, expectedBase, candidate);
            });
        }

        // when: все потоки бьются за один и тот же CAS одновременно
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<SettingsUpdateResult>> futures;
        try {
            futures = executor.invokeAll(calls);
        } finally {
            executor.shutdown();
        }

        Map<String, SettingsUpdateResult> resultByAttemptId = new HashMap<>();
        for (int index = 0; index < THREAD_COUNT; index++) {
            SettingsUpdateResult settingsUpdateResult = futures.get(index).get();
            resultByAttemptId.put(attempts.get(index).attemptId(), settingsUpdateResult);
        }

        // then: ровно один APPLIED, остальные STALE, без FAILED/INTERNAL_FAILURE
        long appliedCount = resultByAttemptId.values().stream()
                .filter(settingsUpdateResult -> settingsUpdateResult.outcome() == SettingsUpdateStatus.APPLIED)
                .count();
        long staleCount = resultByAttemptId.values().stream()
                .filter(settingsUpdateResult -> settingsUpdateResult.outcome() == SettingsUpdateStatus.STALE)
                .count();
        assertThat(appliedCount).isEqualTo(1L);
        assertThat(staleCount).isEqualTo(THREAD_COUNT - 1L);
        assertThat(resultByAttemptId.values())
                .noneMatch(settingsUpdateResult -> settingsUpdateResult.outcome() == SettingsUpdateStatus.FAILED)
                .noneMatch(settingsUpdateResult ->
                        settingsUpdateResult.messageCode() == SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(store.current().acceptedSequence()).isEqualTo(expectedSequence + 1L);

        // then: сумма outcomeCounts равна числу вызовов, без выброшенных исключений в диагностике
        LastSettingsUpdateSnapshot snapshot = diagnostics.snapshot();
        long totalCounted = snapshot.countOf(SettingsUpdateStatus.APPLIED)
                + snapshot.countOf(SettingsUpdateStatus.STALE)
                + snapshot.countOf(SettingsUpdateStatus.REJECTED)
                + snapshot.countOf(SettingsUpdateStatus.UNCHANGED)
                + snapshot.countOf(SettingsUpdateStatus.FAILED);
        assertThat(totalCounted).isEqualTo(THREAD_COUNT);

        // then: latest-кортеж когерентен — latestOutcome/latestMessageCode принадлежат
        // тому же событию, что и latestAttempt, а не смешаны из разных потоков
        SettingsUpdateAttempt latestAttempt = snapshot.latestAttempt();
        assertThat(latestAttempt).isNotNull();
        SettingsUpdateResult resultOfLatestAttempt = resultByAttemptId.get(latestAttempt.attemptId());
        assertThat(resultOfLatestAttempt).isNotNull();
        assertThat(snapshot.latestOutcome()).isEqualTo(resultOfLatestAttempt.outcome());
        assertThat(snapshot.latestMessageCode()).isEqualTo(resultOfLatestAttempt.messageCode());
    }

    private static SettingsUpdateAttempt attempt(String attemptId) {
        return new SettingsUpdateAttempt(
                attemptId,
                Instant.now(),
                SettingsSourceAuthority.CONFIG_SERVER,
                SettingsUpdateTrigger.REFRESH,
                "revision-concurrency"
        );
    }

    private static TracingSettings policy(double samplingRatio) {
        return TracingSettings.of(
                true,
                samplingRatio,
                Map.of(),
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO
        );
    }
}
