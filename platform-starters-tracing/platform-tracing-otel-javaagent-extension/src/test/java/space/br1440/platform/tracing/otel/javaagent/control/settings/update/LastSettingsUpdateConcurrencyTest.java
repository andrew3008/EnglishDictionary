package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

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

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

class LastSettingsUpdateConcurrencyTest {

    private static final int THREAD_COUNT = 32;
    private static final String RUNTIME_INSTANCE_ID = "00000000-0000-0000-0000-000000000042";
    private static final Instant BASE_OBSERVED_AT = Instant.parse("2026-08-23T05:21:44Z");

    @Test
    void concurrentApply_hasExactlyOneWinner_andCoherentDiagnostics() throws Exception {
        // given
        TracingSettings baseline = policy(0.10d);
        TracingSettings candidate = policy(0.91d);
        TracingSettingsStore store = new TracingSettingsStore(RUNTIME_INSTANCE_ID, baseline);
        long expectedSequence = store.current().acceptedSequence();
        ExpectedSettingsRevision expectedBase =
                new ExpectedSettingsRevision(RUNTIME_INSTANCE_ID, expectedSequence);

        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(store, diagnostics);

        List<SettingsUpdateAttempt> attempts = new ArrayList<>(THREAD_COUNT);
        for (int index = 0; index < THREAD_COUNT; index++) {
            attempts.add(attempt(index + 1L));
        }

        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        List<Callable<SettingsUpdateResult>> calls = new ArrayList<>(THREAD_COUNT);
        for (SettingsUpdateAttempt attempt : attempts) {
            calls.add(() -> {
                barrier.await();
                return updater.apply(attempt, expectedBase, candidate);
            });
        }

        // when
        List<Future<SettingsUpdateResult>> futures;
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            futures = executor.invokeAll(calls);
        }

        Map<String, SettingsUpdateResult> resultByAttemptId = new HashMap<>();
        for (int index = 0; index < THREAD_COUNT; index++) {
            SettingsUpdateResult settingsUpdateResult = futures.get(index).get();
            resultByAttemptId.put(attempts.get(index).attemptId(), settingsUpdateResult);
        }

        // then
        assertThat(resultByAttemptId).hasSize(THREAD_COUNT);
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
        assertThat(resultByAttemptId.values())
                .filteredOn(settingsUpdateResult -> settingsUpdateResult.outcome() == SettingsUpdateStatus.STALE)
                .allMatch(settingsUpdateResult -> settingsUpdateResult.messageCode()
                        == SettingsRejectionReason.ACCEPTED_SEQUENCE_MISMATCH);
        assertThat(store.current().acceptedSequence()).isEqualTo(expectedSequence + 1L);

        LastSettingsUpdateSnapshot snapshot = diagnostics.snapshot();
        assertThat(snapshot.countOf(SettingsUpdateStatus.APPLIED)).isEqualTo(1L);
        assertThat(snapshot.countOf(SettingsUpdateStatus.STALE)).isEqualTo(THREAD_COUNT - 1L);
        assertThat(snapshot.countOf(SettingsUpdateStatus.REJECTED)).isZero();
        assertThat(snapshot.countOf(SettingsUpdateStatus.UNCHANGED)).isZero();
        assertThat(snapshot.countOf(SettingsUpdateStatus.FAILED)).isZero();
        long totalCounted = snapshot.countOf(SettingsUpdateStatus.APPLIED)
                + snapshot.countOf(SettingsUpdateStatus.STALE)
                + snapshot.countOf(SettingsUpdateStatus.REJECTED)
                + snapshot.countOf(SettingsUpdateStatus.UNCHANGED)
                + snapshot.countOf(SettingsUpdateStatus.FAILED);
        assertThat(totalCounted).isEqualTo(THREAD_COUNT);

        SettingsUpdateAttempt latestAttempt = snapshot.latestAttempt();
        assertThat(latestAttempt).isNotNull();
        SettingsUpdateResult resultOfLatestAttempt = resultByAttemptId.get(latestAttempt.attemptId());
        assertThat(resultOfLatestAttempt).isNotNull();
        assertThat(snapshot.latestOutcome()).isEqualTo(resultOfLatestAttempt.outcome());
        assertThat(snapshot.latestMessageCode()).isEqualTo(resultOfLatestAttempt.messageCode());
    }

    private static SettingsUpdateAttempt attempt(long ordinal) {
        return new SettingsUpdateAttempt(
                new UUID(0L, ordinal).toString(),
                BASE_OBSERVED_AT.plusNanos(ordinal),
                SettingsSourceAuthority.CONFIG_SERVER,
                SettingsUpdateTrigger.REFRESH,
                "revision-concurrency"
        );
    }

    private static TracingSettings policy(double samplingRatio) {
        return TracingSettings.of(
                true,
                samplingRatio,
                Map.of(), SpanAttributeValidationLevel.WARNING, true, true, AgentLogLevel.INFO
        );
    }
}
