package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OwnerCaptureTest {

    private static final long INDUCED_CAPTURE_DELAY_MILLIS = 100L;

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void offsetNanos_reflectsSlotCaptureDuration_notCallerOverhead() throws Exception {
        // given
        OwnerRegistrationState.Absent<String> absentState = new OwnerRegistrationState.Absent<>(0L);

        @SuppressWarnings("unchecked")
        OwnerRegistrationSlot<String> slot = mock(OwnerRegistrationSlot.class);
        when(slot.capture()).thenAnswer(invocation -> {
            Thread.sleep(INDUCED_CAPTURE_DELAY_MILLIS);
            return absentState;
        });

        // when
        OwnerCapture<String> capture = OwnerCapture.of(slot);

        // then
        assertThat(capture.state()).isSameAs(absentState);
        assertThat(capture.offsetNanos())
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(INDUCED_CAPTURE_DELAY_MILLIS) / 2);
    }

    @Test
    void of_returnsPresentState_fromSlotCapture() {
        // given
        OwnerRegistrationState.Present<String> presentState =
                new OwnerRegistrationState.Present<>("owner", 1L);

        @SuppressWarnings("unchecked")
        OwnerRegistrationSlot<String> slot = mock(OwnerRegistrationSlot.class);
        when(slot.capture()).thenReturn(presentState);

        // when
        OwnerCapture<String> capture = OwnerCapture.of(slot);

        // then
        assertThat(capture.state()).isSameAs(presentState);
        assertThat(capture.offsetNanos()).isGreaterThanOrEqualTo(0L);
    }
}
