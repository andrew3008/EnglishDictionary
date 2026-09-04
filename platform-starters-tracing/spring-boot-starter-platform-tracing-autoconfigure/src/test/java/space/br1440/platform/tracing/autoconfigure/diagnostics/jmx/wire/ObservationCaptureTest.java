package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ObservationCaptureTest {

    @Test
    void nonPositiveEnvelopeValuesAreRejected() {
        // given / when / then
        assertThatThrownBy(() -> new ObservationCapture("agent-1", 0L, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");

        assertThatThrownBy(() -> new ObservationCapture("agent-1", 1L, 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startedAtEpochMilli");

        assertThatThrownBy(() -> new ObservationCapture("agent-1", 1L, 1L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completedAtEpochMilli");

        assertThatThrownBy(() -> new ObservationCapture(null, 1L, 1L, 1L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void completedBeforeStartedIsAcceptedBecauseWallClockCanStepBackwards() {
        // given
        long startedAt = 1_700_000_000_005L;
        long completedAt = 1_700_000_000_000L;

        // when
        ObservationCapture capture = new ObservationCapture("agent-1", 1L, startedAt, completedAt);

        // then
        assertThat(capture.startedAtEpochMilli()).isEqualTo(startedAt);
        assertThat(capture.completedAtEpochMilli()).isEqualTo(completedAt);
    }
}
