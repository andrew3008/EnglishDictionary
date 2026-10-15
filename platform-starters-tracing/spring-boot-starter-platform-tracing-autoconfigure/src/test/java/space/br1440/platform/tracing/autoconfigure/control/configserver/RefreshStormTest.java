package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.delivery;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.recordingSettingsWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;

class RefreshStormTest {

    private static final int EVENT_COUNT = 12;
    private static final String POLICY_KEY = "platform.tracing.policy.sampling.ratio";
    private static final String AGENT_INSTANCE_ID = "959e0cc5-c375-4424-94a6-0595f8deec44";

    @Test
    void concurrentEventsAreSerializedWithoutLosingLastGeneration() throws Exception {
        // given
        AtomicInteger activeBuilds = new AtomicInteger();
        AtomicInteger maximumActiveBuilds = new AtomicInteger();
        AtomicInteger generation = new AtomicInteger();
        CountDownLatch firstBuildEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstBuild = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(EVENT_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch refreshInvoked = new CountDownLatch(EVENT_COUNT);

        ConfigServerSettingsManifestBuilder manifestBuilder = mock(ConfigServerSettingsManifestBuilder.class);
        when(manifestBuilder.build()).thenAnswer(invocation -> {
            int active = activeBuilds.incrementAndGet();
            maximumActiveBuilds.accumulateAndGet(active, Math::max);
            int currentGeneration = generation.incrementAndGet();
            try {
                if (currentGeneration == 1) {
                    firstBuildEntered.countDown();
                    if (!releaseFirstBuild.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("First manifest build release timed out");
                    }
                }
                return snapshot(currentGeneration / (double) EVENT_COUNT);
            } finally {
                activeBuilds.decrementAndGet();
            }
        });

        List<CompleteTracingSettings> applied = new CopyOnWriteArrayList<>();
        AcceptedSettings accepted = AcceptedSettingsTestFixtures.configServerAccepted(
                4L,
                AGENT_INSTANCE_ID,
                snapshot(1.0d)
        );
        RecordingSettingsWriter settingsWriter = recordingSettingsWriter(
                (base, snapshot, writer) -> {
                    applied.add(snapshot);
                    return delivery(TracingSettingsDeliveryResult.APPLIED);
                },
                () -> Optional.of(accepted)
        );
        ConfigServerTracingSettingsSynchronizer synchronizer =
                new ConfigServerTracingSettingsSynchronizer(manifestBuilder, settingsWriter);
        ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(synchronizer);

        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(EVENT_COUNT)) {
            List<Future<?>> futures = new ArrayList<>(EVENT_COUNT);
            for (int event = 0; event < EVENT_COUNT; event++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Refresh start timed out");
                    }
                    refreshInvoked.countDown();
                    adapter.onEnvironmentChange(new EnvironmentChangeEvent(Set.of(POLICY_KEY)));
                    return null;
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(firstBuildEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(refreshInvoked.await(5, TimeUnit.SECONDS)).isTrue();
            releaseFirstBuild.countDown();

            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }

        // then
        assertThat(maximumActiveBuilds.get()).isEqualTo(1);
        assertThat(generation.get()).isEqualTo(EVENT_COUNT);
        assertThat(settingsWriter.applyCallCount()).isEqualTo(EVENT_COUNT);
        assertThat(applied).hasSize(EVENT_COUNT);
        assertThat(applied.getLast().samplingRatio()).isEqualTo(1.0d);
    }

    private static CompleteTracingSettings snapshot(double ratio) {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(ratio)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }
}
