package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.configuration.spi.DropOldestExportProcessorDefaults;

import java.time.Duration;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Builder validation: production-safe — невалидные значения откатываются на безопасные дефолты,
 * вместо fail-fast. Это политика agent extension'а: опечатка в конфиге не должна ронять JVM.
 */
class PlatformDropOldestExportSpanProcessorBuilderValidationTest {

    private static final SpanExporter NOOP = new SpanExporter() {
        @Override public CompletableResultCode export(Collection<SpanData> batch) { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    };

    @Test
    @DisplayName("Null exporter обязателен — единственный fail-fast случай")
    void nullExporterIsRejectedAtBuildTime() {
        assertThatThrownBy(() -> PlatformDropOldestExportSpanProcessor.builder(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("exporter");
    }

    @Test
    @DisplayName("maxQueueSize <= 0 → fallback на DEFAULT_BSP_MAX_QUEUE_SIZE")
    void zeroQueueSizeFallsBackToDefault() {
        PlatformDropOldestExportSpanProcessor p = PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .maxQueueSize(0)
                .build();
        try {
            assertThat(p.getQueueCapacity()).isEqualTo(DropOldestExportProcessorDefaults.defaultMaxQueueSize());
        } finally {
            p.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("maxExportBatchSize > maxQueueSize → clamp до maxQueueSize")
    void batchSizeGreaterThanQueueClampsToQueue() {
        PlatformDropOldestExportSpanProcessor p = PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .maxQueueSize(16)
                .maxExportBatchSize(64)
                .build();
        try {
            assertThat(p.getQueueCapacity()).isEqualTo(16);
            // Внутренний batch size не выставлен наружу, поэтому проверяем косвенно: процессор
            // строится без ошибки, не виснет, queueSize=0.
            assertThat(p.getQueueSize()).isZero();
        } finally {
            p.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Negative durations → fallback на платформенные дефолты")
    void negativeDurationsFallBackToDefaults() {
        PlatformDropOldestExportSpanProcessor p = PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .scheduleDelay(Duration.ofMillis(-1))
                .exportTimeout(Duration.ofSeconds(0))
                .shutdownTimeout(Duration.ofSeconds(-10))
                .build();
        try {
            // Все три должны были откатиться без падения.
            assertThat(p.getQueueCapacity()).isPositive();
        } finally {
            p.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Маленький, но валидный maxQueueSize (например, 4 для тестов) принимается без подъёма")
    void smallValidQueueSizeIsAccepted() {
        PlatformDropOldestExportSpanProcessor p = PlatformDropOldestExportSpanProcessor.builder(NOOP)
                .maxQueueSize(4)
                .maxExportBatchSize(2)
                .build();
        try {
            assertThat(p.getQueueCapacity())
                    .as("маленький capacity не должен молча подменяться — обоснование см. в Builder")
                    .isEqualTo(4);
        } finally {
            p.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
