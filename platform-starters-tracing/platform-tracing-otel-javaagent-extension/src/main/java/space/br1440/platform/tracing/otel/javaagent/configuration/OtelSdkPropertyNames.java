package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class OtelSdkPropertyNames {

    public static final String PROPAGATORS = "otel.propagators";

    public static final String BSP_MAX_QUEUE_SIZE = "otel.bsp.max.queue.size";
    public static final String BSP_MAX_EXPORT_BATCH_SIZE = "otel.bsp.max.export.batch.size";
    public static final String BSP_SCHEDULE_DELAY = "otel.bsp.schedule.delay";
    public static final String BSP_EXPORT_TIMEOUT = "otel.bsp.export.timeout";

}
