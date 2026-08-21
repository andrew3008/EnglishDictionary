package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.HashMap;
import java.util.Map;

import space.br1440.platform.tracing.otel.javaagent.resource.PlatformResourceCustomizer;
import space.br1440.platform.common.util.StringUtils;

public final class PlatformTracingDefaultsProvider {

    @FunctionalInterface
    public interface EnvSupplier {
        String get(String name);
    }

    private final EnvSupplier envSupplier;

    public PlatformTracingDefaultsProvider() {
        this(System::getenv);
    }

    public PlatformTracingDefaultsProvider(EnvSupplier envSupplier) {
        this.envSupplier = envSupplier;
    }

    public Map<String, String> supply() {
        Map<String, String> defaults = getDefaults();
        putEnvFallback(defaults, ExtensionEnvironmentVariables.QUEUE_OVERFLOW_POLICY, ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY);
        putEnvFallback(defaults, ExtensionEnvironmentVariables.SERVICE_NAME, PlatformResourceCustomizer.PROP_SERVICE_NAME);
        putEnvFallback(defaults, ExtensionEnvironmentVariables.SERVICE_VERSION, PlatformResourceCustomizer.PROP_SERVICE_VERSION);
        putEnvFallback(defaults, ExtensionEnvironmentVariables.SERVICE_ENVIRONMENT, PlatformResourceCustomizer.PROP_ENVIRONMENT);
        putEnvFallback(defaults, ExtensionEnvironmentVariables.SERVICE_C_GROUP, PlatformResourceCustomizer.PROP_C_GROUP);
        putEnvFallback(defaults, ExtensionEnvironmentVariables.RESOURCE_POLICY_VERSION, ExtensionPropertyNames.RESOURCE_POLICY_VERSION);
        return defaults;
    }

    private static Map<String, String> getDefaults() {
        Map<String, String> defaults = new HashMap<>();

        defaults.put("otel.bsp.max.queue.size", String.valueOf(OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE));
        defaults.put("otel.bsp.max.export.batch.size", String.valueOf(OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE));
        defaults.put("otel.bsp.schedule.delay", String.valueOf(OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY.toMillis()));
        defaults.put("otel.bsp.export.timeout", String.valueOf(OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT.toMillis()));

        defaults.put("otel.span.attribute.count.limit", String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_COUNT_LIMIT));
        defaults.put("otel.span.attribute.value.length.limit", String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT));
        defaults.put("otel.span.event.count.limit", String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_EVENT_COUNT_LIMIT));
        defaults.put("otel.span.link.count.limit", String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_LINK_COUNT_LIMIT));

        defaults.put("otel.attribute.count.limit", String.valueOf(OtelSdkDefaults.DEFAULT_ATTRIBUTE_COUNT_LIMIT));

        defaults.put("otel.instrumentation.common.logging.trace-id-key", "traceId");
        defaults.put("otel.instrumentation.common.logging.span-id-key", "spanId");
        defaults.put("otel.instrumentation.common.logging.trace-flags-key", "traceFlags");

        return defaults;
    }

    private void putEnvFallback(Map<String, String> defaults, String envKey, String propKey) {
        String value = envSupplier.get(envKey);
        if (StringUtils.hasText(value)) {
            defaults.put(propKey, value.trim());
        }
    }
}
