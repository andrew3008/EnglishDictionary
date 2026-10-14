package space.br1440.platform.tracing.autoconfigure.control.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Map;

import org.junit.jupiter.api.Test;

final class AcceptedSettingsTest {

    private static final String AGENT_INSTANCE_ID = "5a5a6bcc-6bbe-41e2-8dcb-e30287931a2c";

    @Test
    void acceptsCanonicalCompleteState() {
        // given
        SettingsRevision revision = new SettingsRevision(AGENT_INSTANCE_ID, 7L);
        CompleteTracingSettings policy = settings();

        // when
        AcceptedSettings accepted =
                new AcceptedSettings(revision, policy, AcceptedSettingsAuthority.BOOTSTRAP);

        // then
        assertThat(accepted.revision()).isSameAs(revision);
        assertThat(accepted.policy()).isSameAs(policy);
        assertThat(accepted.authority()).isEqualTo(AcceptedSettingsAuthority.BOOTSTRAP);
    }

    @Test
    void rejectsMissingRevision() {
        // given
        CompleteTracingSettings policy = settings();

        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AcceptedSettings(
                        null,
                        policy,
                        AcceptedSettingsAuthority.BOOTSTRAP
                ))
                .withMessage("revision");
    }

    @Test
    void rejectsMissingPolicy() {
        // given
        SettingsRevision revision = new SettingsRevision(AGENT_INSTANCE_ID, 0L);

        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AcceptedSettings(
                        revision,
                        null,
                        AcceptedSettingsAuthority.BOOTSTRAP
                ))
                .withMessage("policy");
    }

    @Test
    void rejectsMissingAuthority() {
        // given
        SettingsRevision revision = new SettingsRevision(AGENT_INSTANCE_ID, 0L);
        CompleteTracingSettings policy = settings();

        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AcceptedSettings(revision, policy, null))
                .withMessage("authority");
    }

    private static CompleteTracingSettings settings() {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5d)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }
}
