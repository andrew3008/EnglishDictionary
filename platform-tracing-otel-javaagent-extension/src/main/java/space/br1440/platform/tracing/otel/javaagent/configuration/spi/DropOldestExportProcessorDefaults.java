package space.br1440.platform.tracing.otel.javaagent.configuration.spi;

import lombok.experimental.UtilityClass;

import java.time.Duration;

@UtilityClass
public final class DropOldestExportProcessorDefaults {

    public static int defaultMaxQueueSize() {
        return OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE;
    }

    public static int defaultMaxExportBatchSize() {
        return OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE;
    }

    public static Duration defaultScheduleDelay() {
        return OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY;
    }

    public static Duration defaultExportTimeout() {
        return OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT;
    }
}
