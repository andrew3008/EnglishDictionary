package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.trace.export.SpanExporter;

class PlatformDropOldestExportSpanProcessorBuilderValidationTest {

    private static final SpanExporter NOOP = SpanExporter.noop();

    @Test
    void nullExporterIsRejectedAtBuildTime() {
        // given/when/then
        assertThatThrownBy(() -> PlatformDropOldestExportSpanProcessor.builder(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("exporter");
    }

    @Test
    void zeroQueueSizeIsRejected() {
        // given/when/then
        assertThatThrownBy(() -> PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .maxQueueSize(0)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxQueueSize");
    }

    @Test
    void batchSizeGreaterThanQueueClampsToQueue() {
        // given/when
        PlatformDropOldestExportSpanProcessor p = PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .maxQueueSize(16)
                .maxExportBatchSize(64)
                .build();
        try {
            // then
            assertThat(p.metricsSnapshot().queueCapacity()).isEqualTo(16);
            assertThat(p.metricsSnapshot().queueSize()).isZero();
        } finally {
            p.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void nonPositiveDurationsAreRejected() {
        // given/when/then
        assertThatThrownBy(() -> PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .scheduleDelay(Duration.ofMillis(-1))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduleDelay");

        assertThatThrownBy(() -> PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .exportTimeout(Duration.ZERO)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exportTimeout");

        assertThatThrownBy(() -> PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .shutdownTimeout(Duration.ofSeconds(-10))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shutdownTimeout");
    }

    @Test
    void smallValidQueueSizeIsAccepted() {
        // given/when
        PlatformDropOldestExportSpanProcessor p = PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .maxQueueSize(4)
                .maxExportBatchSize(2)
                .build();

        try {
            // then
            assertThat(p.metricsSnapshot().queueCapacity())
                    .as("маленький capacity не должен молча подменяться — обоснование см. в Builder")
                    .isEqualTo(4);
        } finally {
            p.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
