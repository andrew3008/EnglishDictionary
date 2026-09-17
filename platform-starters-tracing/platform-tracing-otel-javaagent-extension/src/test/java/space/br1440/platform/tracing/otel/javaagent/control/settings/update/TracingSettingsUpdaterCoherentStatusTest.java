package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

class TracingSettingsUpdaterCoherentStatusTest {

    @Test
    void captureWaitsUntilAcceptedRevisionAndDiagnosticsArePublishedTogether() throws Exception {
        // given
        TracingSettingsStore store = new TracingSettingsStore(
                UUID.randomUUID().toString(),
                settings(1.0d));
        LastSettingsUpdate diagnostics = spy(new LastSettingsUpdate());
        CountDownLatch recordEntered = new CountDownLatch(1);
        CountDownLatch releaseRecord = new CountDownLatch(1);
        doAnswer(invocation -> {
            recordEntered.countDown();
            if (!releaseRecord.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release settings diagnostics");
            }
            return invocation.callRealMethod();
        }).when(diagnostics).record(
                any(SettingsUpdateAttempt.class),
                any(SettingsUpdateResult.class));

        TracingSettingsUpdater updater = new TracingSettingsUpdater(store, diagnostics);
        SettingsUpdateAttempt attempt = new SettingsUpdateAttempt(
                UUID.randomUUID().toString(),
                Instant.parse("2026-08-24T04:00:00Z"),
                SettingsSourceAuthority.CONFIG_SERVER,
                SettingsUpdateTrigger.REFRESH,
                "revision-coherence");
        TracingSettings candidate = settings(0.25d);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<SettingsStatusSnapshot> captured = new AtomicReference<>();
        AtomicReference<Throwable> captureFailure = new AtomicReference<>();
        CountDownLatch captureStarted = new CountDownLatch(1);
        Thread captureThread = new Thread(() -> {
            captureStarted.countDown();
            try {
                captured.set(updater.captureStatus());
            } catch (Throwable failure) {
                captureFailure.set(failure);
            }
        }, "platform-settings-status-capture-test");
        captureThread.setDaemon(true);

        try {
            Future<SettingsUpdateResult> updateFuture = executor.submit(
                    () -> updater.apply(attempt, store.current().base(), candidate));
            assertThat(recordEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(store.current().acceptedSequence()).isEqualTo(1L);
            assertThat(diagnostics.snapshot().latestAttempt()).isNull();

            // when
            captureThread.start();
            assertThat(captureStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(captureThread);

            // then
            assertThat(captureThread.getState()).isEqualTo(Thread.State.BLOCKED);
            assertThat(captured.get()).isNull();

            releaseRecord.countDown();
            SettingsUpdateResult result = updateFuture.get(5, TimeUnit.SECONDS);
            captureThread.join(TimeUnit.SECONDS.toMillis(5));

            assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
            assertThat(captureThread.isAlive()).isFalse();
            assertThat(captureFailure.get()).isNull();
            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().accepted().acceptedSequence()).isEqualTo(1L);
            assertThat(captured.get().accepted().policy()).isEqualTo(candidate);
            assertThat(captured.get().update().latestAttempt()).isEqualTo(attempt);
            assertThat(captured.get().update().latestOutcome())
                    .isEqualTo(SettingsUpdateStatus.APPLIED);
            assertThat(captured.get().update().latestMessageCode())
                    .isEqualTo(SettingsRejectionReason.NONE);
        } finally {
            releaseRecord.countDown();
            captureThread.interrupt();
            captureThread.join(TimeUnit.SECONDS.toMillis(5));
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive()
                && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static TracingSettings settings(double samplingRatio) {
        return TracingSettings.of(
                true,
                samplingRatio,
                Map.of(),
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO);
    }
}
