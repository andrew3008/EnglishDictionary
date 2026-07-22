package space.br1440.platform.tracing.otel.extension.configuration.spi;

import lombok.experimental.UtilityClass;

import java.time.Duration;

@UtilityClass
final class OtelSdkDefaults {

    static final int DEFAULT_BSP_MAX_QUEUE_SIZE = 2048;
    static final int DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE = 512;
    static final Duration DEFAULT_BSP_EXPORT_TIMEOUT = Duration.ofMillis(5_000);
    static final Duration DEFAULT_BSP_SCHEDULE_DELAY = Duration.ofMillis(5_000);

    static final int DEFAULT_SPAN_ATTRIBUTE_COUNT_LIMIT = 50;
    static final int DEFAULT_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT = 1_000;
    static final int DEFAULT_SPAN_EVENT_COUNT_LIMIT = 10;
    static final int DEFAULT_SPAN_LINK_COUNT_LIMIT = 32;
    static final int DEFAULT_ATTRIBUTE_COUNT_LIMIT = 16;

}
