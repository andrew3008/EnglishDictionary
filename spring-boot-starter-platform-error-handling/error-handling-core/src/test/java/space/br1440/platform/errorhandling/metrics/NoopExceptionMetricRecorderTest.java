package space.br1440.platform.errorhandling.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class NoopExceptionMetricRecorderTest {

    private final NoopExceptionMetricRecorder recorder = new NoopExceptionMetricRecorder();

    private ExceptionMetricSnapshot snapshot() {
        return new ExceptionMetricSnapshot(
                "TestStrategy",
                "java.lang.RuntimeException",
                500,
                1_000_000,
                null,
                null,
                null
        );
    }

    @Test
    @DisplayName("implements ExceptionMetricRecorder interface")
    void implementsInterface() {
        // when / then
        assertThat(recorder).isInstanceOf(ExceptionMetricRecorder.class);
    }

    @Test
    @DisplayName("recordHandled does not throw")
    void recordHandledNoOp() {
        // when / then
        assertThatCode(() -> recorder.recordHandled(snapshot())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("recordUnhandled does not throw")
    void recordUnhandledNoOp() {
        // when / then
        assertThatCode(() -> recorder.recordUnhandled(snapshot())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("multiple calls do not throw")
    void multipleCallsNoOp() {
        // when / then
        assertThatCode(() -> {
            for (int i = 0; i < 100; i++) {
                recorder.recordHandled(snapshot());
                recorder.recordUnhandled(snapshot());
            }
        }).doesNotThrowAnyException();
    }
}
