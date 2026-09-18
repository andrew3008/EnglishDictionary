package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.trace.export.SpanExporter;

class PlatformDropOldestExportSpanProcessorBuilderValidationTest {

    private static final SpanExporter NOOP = SpanExporter.noop();

    @Test
    @DisplayName("Null exporter обязателен — единственный fail-fast случай")
    void nullExporterIsRejectedAtBuildTime() {
        // given/when/then
        assertThatThrownBy(() -> PlatformDropOldestExportSpanProcessor.builder(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("exporter");
    }

    @Test
    @DisplayName("maxQueueSize <= 0 → fallback на DEFAULT_BSP_MAX_QUEUE_SIZE")
    void zeroQueueSizeFallsBackToDefault() {
        // given/when
        PlatformDropOldestExportSpanProcessor p = PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .maxQueueSize(0)
                .build();
        try {
            // then
            assertThat(p.metricsSnapshot().queueCapacity()).isEqualTo(2048);
        } finally {
            p.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("maxExportBatchSize > maxQueueSize → clamp до maxQueueSize")
    void batchSizeGreaterThanQueueClampsToQueue() {
        // given/when
        PlatformDropOldestExportSpanProcessor p = PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .maxQueueSize(16)
                .maxExportBatchSize(64)
                .build();
        try {
            // then
            assertThat(p.metricsSnapshot().queueCapacity()).isEqualTo(16);
            // Внутренний batch size не выставлен наружу, поэтому проверяем косвенно: процессор
            // строится без ошибки, не виснет, queueSize=0.
            assertThat(p.metricsSnapshot().queueSize()).isZero();
        } finally {
            p.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Negative durations → fallback на платформенные дефолты")
    void negativeDurationsFallBackToDefaults() {
        // given/when
        PlatformDropOldestExportSpanProcessor p = PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .scheduleDelay(Duration.ofMillis(-1))
                .exportTimeout(Duration.ofSeconds(0))
                .shutdownTimeout(Duration.ofSeconds(-10))
                .build();
        try {
            // then
            // Все три должны были откатиться без падения.
            assertThat(p.metricsSnapshot().queueCapacity()).isPositive();
        } finally {
            p.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Маленький, но валидный maxQueueSize (например, 4 для тестов) принимается без подъёма")
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
