package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class OtelSdkPropertyNames {

    public static final String PROPAGATORS = "otel.propagators";
    public static final String OTLP_RETRY_DISABLED = "otel.java.exporter.otlp.retry.disabled";

    public static final String BSP_MAX_QUEUE_SIZE = "otel.bsp.max.queue.size";
    public static final String BSP_MAX_EXPORT_BATCH_SIZE = "otel.bsp.max.export.batch.size";
    public static final String BSP_SCHEDULE_DELAY = "otel.bsp.schedule.delay";
    public static final String BSP_EXPORT_TIMEOUT = "otel.bsp.export.timeout";

    public static final String SPAN_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE =
            "otel.java.experimental.span-attributes.copy-from-baggage.include";

    public static final String LOG_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE =
            "otel.java.experimental.log-attributes.copy-from-baggage.include";

}
