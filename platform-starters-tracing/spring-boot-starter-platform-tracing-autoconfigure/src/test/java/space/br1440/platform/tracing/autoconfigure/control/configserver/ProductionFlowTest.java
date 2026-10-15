package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;

class ProductionFlowTest {

    private static final String AGENT_INSTANCE_ID = "66f8525d-95e6-4cf8-a2ca-d384e6fd7b3c";
    private static final SettingsRevision BASE = new SettingsRevision(AGENT_INSTANCE_ID, 7L);
    private static final CompleteTracingSettings INITIAL = snapshot(0.8d, "INFO");
    private static final CompleteTracingSettings UPDATED = snapshot(0.5d, "WARN");

    @Test
    void initialDeliveryWritesCompleteSnapshotAgainstCurrentAgentRevision() {
        // given
        ConfigServerSettingsManifestBuilder manifestBuilder = mock(ConfigServerSettingsManifestBuilder.class);
        TracingSettingsWriter settingsWriter = writerReturning(TracingSettingsDeliveryResult.APPLIED);
        when(manifestBuilder.build()).thenReturn(INITIAL);
        ConfigServerTracingSettingsSynchronizer synchronizer =
                new ConfigServerTracingSettingsSynchronizer(manifestBuilder, settingsWriter);

        // when
        SettingsDelivery delivery = synchronizer.deliverInitial();

        // then
        assertThat(delivery.outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        verify(settingsWriter).applySnapshot(BASE, INITIAL, SettingsWriter.CONFIG_SERVER);
    }

    @Test
    void sequentialRefreshesBuildAndApplyCurrentEnvironmentSnapshotOncePerEvent() {
        // given
        ConfigServerSettingsManifestBuilder manifestBuilder = mock(ConfigServerSettingsManifestBuilder.class);
        TracingSettingsWriter settingsWriter = writerReturning(TracingSettingsDeliveryResult.APPLIED);
        when(manifestBuilder.build()).thenReturn(INITIAL, UPDATED);
        ConfigServerTracingSettingsSynchronizer synchronizer =
                new ConfigServerTracingSettingsSynchronizer(manifestBuilder, settingsWriter);

        // when
        synchronizer.refresh();
        synchronizer.refresh();

        // then
        ArgumentCaptor<CompleteTracingSettings> snapshots = ArgumentCaptor.forClass(CompleteTracingSettings.class);
        verify(settingsWriter, times(2)).applySnapshot(eq(BASE), snapshots.capture(), eq(SettingsWriter.CONFIG_SERVER));
        assertThat(snapshots.getAllValues()).containsExactly(INITIAL, UPDATED);
    }

    @Test
    void staleRefreshIsReturnedWithoutRetry() {
        // given
        ConfigServerSettingsManifestBuilder manifestBuilder = mock(ConfigServerSettingsManifestBuilder.class);
        TracingSettingsWriter settingsWriter = writerReturning(TracingSettingsDeliveryResult.STALE);
        when(manifestBuilder.build()).thenReturn(UPDATED);
        ConfigServerTracingSettingsSynchronizer synchronizer =
                new ConfigServerTracingSettingsSynchronizer(manifestBuilder, settingsWriter);

        // when
        SettingsDelivery delivery = synchronizer.refresh();

        // then
        assertThat(delivery.outcome()).isEqualTo(TracingSettingsDeliveryResult.STALE);
        verify(settingsWriter).applySnapshot(BASE, UPDATED, SettingsWriter.CONFIG_SERVER);
    }

    private static TracingSettingsWriter writerReturning(TracingSettingsDeliveryResult outcome) {
        TracingSettingsWriter settingsWriter = mock(TracingSettingsWriter.class);
        AcceptedSettings accepted = AcceptedSettingsTestFixtures.configServerAccepted(
                BASE.acceptedSequence(),
                BASE.agentInstanceId(),
                INITIAL
        );
        when(settingsWriter.current()).thenReturn(Optional.of(accepted));
        when(settingsWriter.applySnapshot(any(), any(), any())).thenReturn(delivery(outcome));
        return settingsWriter;
    }

    private static CompleteTracingSettings snapshot(double ratio, String logLevel) {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(ratio)
                .samplingRouteRatios(Map.of("/api/orders", ratio))
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel(logLevel)
                .build();
    }

    private static SettingsDelivery delivery(TracingSettingsDeliveryResult outcome) {
        String messageCode = outcome == TracingSettingsDeliveryResult.STALE
                ? "ACCEPTED_SEQUENCE_MISMATCH"
                : "NONE";
        return new SettingsDelivery("00000000-0000-0000-0000-000000000001", outcome, messageCode);
    }
}
