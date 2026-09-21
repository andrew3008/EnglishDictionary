package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProcessorSpanTimeoutValuesTest {

    private static final ProcessorSpanTimeoutValues.ForceEnd FORCE_END = new ProcessorSpanTimeoutValues.ForceEnd(0L, 0L, 0L);
    private static final ProcessorSpanTimeoutValues.Tracking TRACKING =
            new ProcessorSpanTimeoutValues.Tracking(0, 0, 0, 0, 0L, 0, 0, 0L);
    private static final ProcessorSpanTimeoutValues.Rejections REJECTIONS = new ProcessorSpanTimeoutValues.Rejections(0L, 0L, 0L);
    private static final ProcessorSpanTimeoutValues.Scanner SCANNER = new ProcessorSpanTimeoutValues.Scanner(0L, 0L, "RUNNING");

    @Test
    void nullForceEndGroupIsRejected() {
        // given / when / then
        assertThatThrownBy(() -> new ProcessorSpanTimeoutValues(null, TRACKING, REJECTIONS, SCANNER))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTrackingGroupIsRejected() {
        // given / when / then
        assertThatThrownBy(() -> new ProcessorSpanTimeoutValues(FORCE_END, null, REJECTIONS, SCANNER))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullRejectionsGroupIsRejected() {
        // given / when / then
        assertThatThrownBy(() -> new ProcessorSpanTimeoutValues(FORCE_END, TRACKING, null, SCANNER))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullScannerGroupIsRejected() {
        // given / when / then
        assertThatThrownBy(() -> new ProcessorSpanTimeoutValues(FORCE_END, TRACKING, REJECTIONS, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullScannerStateIsRejected() {
        // given / when / then
        assertThatThrownBy(() -> new ProcessorSpanTimeoutValues.Scanner(0L, 0L, null))
                .isInstanceOf(NullPointerException.class);
    }
}
