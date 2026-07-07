package space.br1440.platform.tracing.otel.extension.configuration.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Тест живёт в том же пакете, что и тестируемые классы, чтобы иметь доступ
// к package-private OtelSdkDefaults без расширения его видимости.
class DropOldestExportProcessorDefaultsTest {

    @Test
    void defaultMaxQueueSize_matchesOtelSdkDefaults() {
        assertThat(DropOldestExportProcessorDefaults.defaultMaxQueueSize())
                .isEqualTo(OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE);
    }

    @Test
    void defaultMaxExportBatchSize_matchesOtelSdkDefaults() {
        assertThat(DropOldestExportProcessorDefaults.defaultMaxExportBatchSize())
                .isEqualTo(OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE);
    }

    @Test
    void defaultScheduleDelay_matchesOtelSdkDefaults() {
        assertThat(DropOldestExportProcessorDefaults.defaultScheduleDelay())
                .isEqualTo(OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY);
    }

    @Test
    void defaultExportTimeout_matchesOtelSdkDefaults() {
        assertThat(DropOldestExportProcessorDefaults.defaultExportTimeout())
                .isEqualTo(OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT);
    }
}
