package space.br1440.platform.errorhandling.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerExceptionMetricRecorderTest {

    private MeterRegistry registry;
    private MicrometerExceptionMetricRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new MicrometerExceptionMetricRecorder(registry);
    }

    private ExceptionMetricSnapshot snapshot(int httpStatus, long durationNanos) {
        return new ExceptionMetricSnapshot(
                "TestStrategy",
                "java.lang.IllegalArgumentException",
                httpStatus,
                durationNanos,
                "TEST-001",
                "/api/test",
                "java.lang.RuntimeException"
        );
    }

    @Nested
    @DisplayName("recordHandled()")
    class RecordHandled {

        @Test
        @DisplayName("increments handled counter with correct tags")
        void incrementsHandledCounter() {
            // when
            recorder.recordHandled(snapshot(400, 1_000_000));

            // then
            Counter counter = registry.find(MicrometerExceptionMetricRecorder.METRIC_HANDLED)
                    .tag(MicrometerExceptionMetricRecorder.TAG_STRATEGY, "TestStrategy")
                    .tag(MicrometerExceptionMetricRecorder.TAG_STATUS_CLASS, "4xx")
                    .tag(MicrometerExceptionMetricRecorder.TAG_EXCEPTION_TYPE, "java.lang.IllegalArgumentException")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("records duration in timer")
        void recordsDuration() {
            // when
            recorder.recordHandled(snapshot(500, 5_000_000));

            // then
            Timer timer = registry.find(MicrometerExceptionMetricRecorder.METRIC_DURATION)
                    .tag(MicrometerExceptionMetricRecorder.TAG_STRATEGY, "TestStrategy")
                    .timer();

            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isEqualTo(5_000_000);
        }

        @Test
        @DisplayName("accumulates multiple calls")
        void accumulatesMultipleCalls() {
            // when
            recorder.recordHandled(snapshot(400, 1_000_000));
            recorder.recordHandled(snapshot(400, 2_000_000));

            // then
            Counter counter = registry.find(MicrometerExceptionMetricRecorder.METRIC_HANDLED)
                    .tag(MicrometerExceptionMetricRecorder.TAG_STRATEGY, "TestStrategy")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("status class tag differentiates 4xx vs 5xx")
        void differentiatesStatusClasses() {
            // when
            recorder.recordHandled(snapshot(404, 1_000_000));
            recorder.recordHandled(snapshot(503, 1_000_000));

            // then
            Counter counter4xx = registry.find(MicrometerExceptionMetricRecorder.METRIC_HANDLED)
                    .tag(MicrometerExceptionMetricRecorder.TAG_STATUS_CLASS, "4xx")
                    .counter();
            Counter counter5xx = registry.find(MicrometerExceptionMetricRecorder.METRIC_HANDLED)
                    .tag(MicrometerExceptionMetricRecorder.TAG_STATUS_CLASS, "5xx")
                    .counter();

            assertThat(counter4xx).isNotNull();
            assertThat(counter4xx.count()).isEqualTo(1.0);
            assertThat(counter5xx).isNotNull();
            assertThat(counter5xx.count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("recordUnhandled()")
    class RecordUnhandled {

        @Test
        @DisplayName("increments unhandled counter with exception_type tag")
        void incrementsUnhandledCounter() {
            // when
            recorder.recordUnhandled(snapshot(500, 1_000_000));

            // then
            Counter counter = registry.find(MicrometerExceptionMetricRecorder.METRIC_UNHANDLED)
                    .tag(MicrometerExceptionMetricRecorder.TAG_EXCEPTION_TYPE, "java.lang.IllegalArgumentException")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("records duration in timer for unhandled")
        void recordsDuration() {
            // when
            recorder.recordUnhandled(snapshot(500, 3_000_000));

            // then
            Timer timer = registry.find(MicrometerExceptionMetricRecorder.METRIC_DURATION)
                    .tag(MicrometerExceptionMetricRecorder.TAG_STRATEGY, "TestStrategy")
                    .timer();

            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }
    }
}
