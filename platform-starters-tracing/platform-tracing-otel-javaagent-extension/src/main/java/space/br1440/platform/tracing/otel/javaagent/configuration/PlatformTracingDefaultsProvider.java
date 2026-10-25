package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.HashMap;
import java.util.Map;

import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

public final class PlatformTracingDefaultsProvider {

    public Map<String, String> supply() {
        return getDefaults();
    }

    private static Map<String, String> getDefaults() {
        Map<String, String> defaults = new HashMap<>();

        defaults.put(
                OtelSdkPropertyNames.BSP_MAX_QUEUE_SIZE,
                String.valueOf(OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE)
        );

        defaults.put(
                OtelSdkPropertyNames.BSP_MAX_EXPORT_BATCH_SIZE,
                String.valueOf(OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE)
        );

        defaults.put(
                OtelSdkPropertyNames.BSP_SCHEDULE_DELAY,
                String.valueOf(OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY.toMillis())
        );

        defaults.put(
                OtelSdkPropertyNames.BSP_EXPORT_TIMEOUT,
                String.valueOf(OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT.toMillis())
        );

        defaults.put("otel.span.attribute.count.limit", String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_COUNT_LIMIT));
        defaults.put("otel.span.attribute.value.length.limit", String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT));
        defaults.put("otel.span.event.count.limit", String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_EVENT_COUNT_LIMIT));
        defaults.put("otel.span.link.count.limit", String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_LINK_COUNT_LIMIT));

        defaults.put("otel.attribute.count.limit", String.valueOf(OtelSdkDefaults.DEFAULT_ATTRIBUTE_COUNT_LIMIT));

        defaults.put("otel.instrumentation.common.logging.trace-id-key", TracingMdcKeys.TRACE_ID);
        defaults.put("otel.instrumentation.common.logging.span-id-key", TracingMdcKeys.SPAN_ID);
        defaults.put("otel.instrumentation.common.logging.trace-flags-key", TracingMdcKeys.TRACE_FLAGS);

        return defaults;
    }
}
