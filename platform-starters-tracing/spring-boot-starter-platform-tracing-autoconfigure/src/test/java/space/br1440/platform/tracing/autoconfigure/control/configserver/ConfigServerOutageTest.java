package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;

class ConfigServerOutageTest {

    private static final String POLICY_KEY = "platform.tracing.policy.sampling.ratio";
    private static final String AGENT_INSTANCE_ID = "81b5710a-8b6a-4c8f-9e68-02cc753a08f9";
    private static final CompleteTracingSettings LKG = snapshot(1.0d);

    @Test
    void unrelatedEnvironmentChangeDoesNotTriggerRefresh() {
        // given
        ConfigServerTracingSettingsSynchronizer synchronizer = mock(ConfigServerTracingSettingsSynchronizer.class);
        ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(synchronizer);

        // when
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(this, Set.of("management.endpoints.enabled")));

        // then
        verify(synchronizer, never()).refresh();
    }

    @Test
    void manifestFailureDoesNotEscapeRefreshOrTouchAcceptedSettings() {
        // given
        ConfigServerSettingsManifestBuilder manifestBuilder = mock(ConfigServerSettingsManifestBuilder.class);
        TracingSettingsWriter settingsWriter = mock(TracingSettingsWriter.class);
        when(manifestBuilder.build()).thenThrow(new TracingSettingsManifestException("Required policy key missing"));
        ConfigServerTracingSettingsAdapter adapter = adapter(manifestBuilder, settingsWriter);

        // when / then
        assertThatCode(() -> adapter.onEnvironmentChange(policyChange())).doesNotThrowAnyException();
        verifyNoInteractions(settingsWriter);
    }

    @Test
    void applyFailureDoesNotInterruptApplicationRefreshAndNextEventCanRecover() {
        // given
        ConfigServerSettingsManifestBuilder manifestBuilder = mock(ConfigServerSettingsManifestBuilder.class);
        TracingSettingsWriter settingsWriter = mock(TracingSettingsWriter.class);
        when(manifestBuilder.build()).thenReturn(snapshot(0.5d));
        when(settingsWriter.current()).thenReturn(Optional.of(accepted()));
        when(settingsWriter.applySnapshot(any(), any(), any()))
                .thenThrow(new IllegalStateException("transport unavailable"))
                .thenReturn(delivery());
        ConfigServerTracingSettingsAdapter adapter = adapter(manifestBuilder, settingsWriter);

        // when / then
        assertThatCode(() -> adapter.onEnvironmentChange(policyChange())).doesNotThrowAnyException();
        assertThatCode(() -> adapter.onEnvironmentChange(policyChange())).doesNotThrowAnyException();
        verify(settingsWriter, times(2)).applySnapshot(any(), any(), any());
    }

    private static ConfigServerTracingSettingsAdapter adapter(
            ConfigServerSettingsManifestBuilder manifestBuilder,
            TracingSettingsWriter settingsWriter) {
        ConfigServerTracingSettingsSynchronizer synchronizer =
                new ConfigServerTracingSettingsSynchronizer(manifestBuilder, settingsWriter);
        return new ConfigServerTracingSettingsAdapter(synchronizer);
    }

    private EnvironmentChangeEvent policyChange() {
        return new EnvironmentChangeEvent(this, Set.of(POLICY_KEY));
    }

    private static AcceptedSettings accepted() {
        return AcceptedSettingsTestFixtures.configServerAccepted(3L, AGENT_INSTANCE_ID, LKG);
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

    private static SettingsDelivery delivery() {
        return new SettingsDelivery(
                "00000000-0000-0000-0000-000000000001",
                TracingSettingsDeliveryResult.APPLIED,
                "NONE"
        );
    }
}
