package space.br1440.platform.tracing.autoconfigure.control.settings.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;

final class AcceptedSettingsWithSequenceTest {

    private static final String AGENT_INSTANCE_ID = "5a5a6bcc-6bbe-41e2-8dcb-e30287931a2c";

    @Test
    void acceptsCanonicalCompleteState() {
        // given
        CompleteTracingSettings snapshot = settings();

        // when
        AcceptedSettingsWithSequence accepted =
                new AcceptedSettingsWithSequence(7L, AGENT_INSTANCE_ID, snapshot);

        // then
        assertThat(accepted.expectedBase().agentInstanceId()).isEqualTo(AGENT_INSTANCE_ID);
        assertThat(accepted.expectedBase().acceptedSequence()).isEqualTo(7L);
        assertThat(accepted.snapshot()).isSameAs(snapshot);
    }

    @Test
    void rejectsNegativeAcceptedSequence() {
        // given
        CompleteTracingSettings snapshot = settings();

        // when
        // then
        assertThatThrownBy(() -> new AcceptedSettingsWithSequence(-1L, AGENT_INSTANCE_ID, snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("acceptedSequence must be greater than or equal to zero");
    }

    @Test
    void rejectsNonCanonicalAgentInstanceId() {
        // given
        CompleteTracingSettings snapshot = settings();

        // when
        // then
        assertThatThrownBy(() -> new AcceptedSettingsWithSequence(0L, "agent-1", snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agentInstanceId must be a canonical lowercase UUID");
    }

    @Test
    void rejectsMissingSnapshot() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new AcceptedSettingsWithSequence(0L, AGENT_INSTANCE_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("snapshot");
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
