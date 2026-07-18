package space.br1440.platform.tracing.autoconfigure.actuator;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.support.DurationToMillis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Строит подсказки {@code OTEL_*} env vars из {@link TracingProperties} для SRE / Helm.
 * <p>
 * Значения **не** применяются автоматически в Agent classloader — это dual-channel mapping
 * (Spring {@code platform.tracing.*} vs {@code OTEL_*} / {@code -Dotel.*}). Секция
 * {@code otelEnvHints} в {@code GET /actuator/tracing} показывает, какие env vars выставить,
 * если оператор хочет синхронизировать Helm с {@code application.yml}. Фактически
 * применённые значения — в {@code otelEffective}.
 */
final class OtelEnvHintsBuilder {

    private OtelEnvHintsBuilder() {
    }

    static Map<String, Map<String, Object>> from(TracingProperties properties) {
        Map<String, Map<String, Object>> hints = new LinkedHashMap<>();

        put(hints, "OTEL_BSP_MAX_QUEUE_SIZE", "otel.bsp.max.queue.size",
                String.valueOf(properties.getQueue().getMaxSize()),
                "platform.tracing.queue.max-size");

        put(hints, "OTEL_BSP_MAX_EXPORT_BATCH_SIZE", "otel.bsp.max.export.batch.size",
                String.valueOf(properties.getQueue().getExportBatchSize()),
                "platform.tracing.queue.export-batch-size");

        put(hints, "OTEL_BSP_EXPORT_TIMEOUT", "otel.bsp.export.timeout",
                DurationToMillis.toOtelString(properties.getQueue().getExportTimeout()),
                "platform.tracing.queue.export-timeout");

        put(hints, "OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT", "otel.span.attribute.count.limit",
                String.valueOf(properties.getLimits().getMaxAttributes()),
                "platform.tracing.limits.max-attributes");

        put(hints, "OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT", "otel.span.attribute.value.length.limit",
                String.valueOf(properties.getLimits().getMaxAttributeValueLength()),
                "platform.tracing.limits.max-attribute-value-length");

        put(hints, "OTEL_SPAN_EVENT_COUNT_LIMIT", "otel.span.event.count.limit",
                String.valueOf(properties.getLimits().getMaxEvents()),
                "platform.tracing.limits.max-events");

        // -- Exporter (OTLP) -------------------------------------------------------------------
        put(hints, "OTEL_EXPORTER_OTLP_ENDPOINT", "otel.exporter.otlp.endpoint",
                properties.getExporter().getOtlp().getEndpoint(),
                "platform.tracing.exporter.otlp.endpoint");

        // OTel-ключ инвертирован: retry.disabled = !retry.enabled. Подсказываем именно отключение,
        // т.к. это стандартный knob OTel Java SDK (retry включён по умолчанию с 1.59+).
        put(hints, "OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED", "otel.java.exporter.otlp.retry.disabled",
                String.valueOf(!properties.getExporter().getOtlp().getRetry().isEnabled()),
                "platform.tracing.exporter.otlp.retry.enabled");

        // -- Queue overflow policy -------------------------------------------------------------
        // Особый случай: платформенный namespace PLATFORM_TRACING_* (не OTEL_*). Управляет
        // активацией PlatformDropOldestExportSpanProcessor (DROP_OLDEST) либо stock BSP (UPSTREAM).
        put(hints, "PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY", "platform.tracing.queue.overflow-policy",
                properties.getQueue().getPolicy().name(),
                "platform.tracing.queue.policy");

        put(hints, "PLATFORM_TRACING_CONTROL_RUNTIME_MUTATION_ENABLED",
                "platform.tracing.control.runtime-mutation.enabled",
                String.valueOf(properties.getControl().getRuntimeMutation().isEnabled()),
                "platform.tracing.control.runtime-mutation.enabled");

        // Resource identity (Фаза 9): платформенный namespace PLATFORM_TRACING_* (не OTEL_*).
        // Эти env-переменные пробрасываются env-bridge'ем PlatformTracingDefaultsProvider в Agent mode.
        putResourceHint(hints, "PLATFORM_TRACING_SERVICE_NAME",
                "platform.tracing.service.name", properties.getService().getName());
        putResourceHint(hints, "PLATFORM_TRACING_SERVICE_VERSION",
                "platform.tracing.service.version", properties.getService().getVersion());
        putResourceHint(hints, "PLATFORM_TRACING_SERVICE_ENVIRONMENT",
                "platform.tracing.service.environment", properties.getService().getEnvironment());
        putResourceHint(hints, "PLATFORM_TRACING_SERVICE_C_GROUP",
                "platform.tracing.service.c-group", properties.getService().getCGroup());
        putResourceHint(hints, "PLATFORM_TRACING_RESOURCE_POLICY_VERSION",
                "platform.tracing.resource.policy-version", properties.getResource().getPolicyVersion());

        // -- Outbound propagation (Фаза 12) ----------------------------------------------------
        // Платформенный namespace PLATFORM_TRACING_* (не OTEL_*): инжекция платформенных заголовков —
        // зона платформенных client-интерсепторов (Spring), не Agent SDK-цепочки.
        TracingProperties.Propagation.Outbound outbound = properties.getPropagation().getOutbound();
        put(hints, "PLATFORM_TRACING_PROPAGATION_OUTBOUND_ENABLED",
                "platform.tracing.propagation.outbound.enabled",
                String.valueOf(outbound.isEnabled()),
                "platform.tracing.propagation.outbound.enabled");
        put(hints, "PLATFORM_TRACING_PROPAGATION_OUTBOUND_TRUSTED_HOST_PATTERNS",
                "platform.tracing.propagation.outbound.trusted-host-patterns",
                String.join(",", outbound.getTrustedHostPatterns()),
                "platform.tracing.propagation.outbound.trusted-host-patterns");
        put(hints, "PLATFORM_TRACING_PROPAGATION_OUTBOUND_PROPAGATE_REQUEST_ID",
                "platform.tracing.propagation.outbound.propagate-request-id",
                String.valueOf(outbound.isPropagateRequestId()),
                "platform.tracing.propagation.outbound.propagate-request-id");

        // -- Kafka outbound (Фаза 12) ----------------------------------------------------------
        TracingProperties.Kafka kafka = properties.getKafka();
        put(hints, "PLATFORM_TRACING_KAFKA_MODE",
                "platform.tracing.kafka.mode", kafka.getMode(),
                "platform.tracing.kafka.mode");
        put(hints, "PLATFORM_TRACING_KAFKA_PROPAGATE_PLATFORM_HEADERS",
                "platform.tracing.kafka.propagate-platform-headers",
                String.valueOf(kafka.isPropagatePlatformHeaders()),
                "platform.tracing.kafka.propagate-platform-headers");
        put(hints, "PLATFORM_TRACING_KAFKA_TRUSTED_TOPIC_PATTERNS",
                "platform.tracing.kafka.trusted-topic-patterns",
                String.join(",", kafka.getTrustedTopicPatterns()),
                "platform.tracing.kafka.trusted-topic-patterns");

        return hints;
    }

    private static void putResourceHint(Map<String, Map<String, Object>> hints,
                                        String envVar,
                                        String platformProperty,
                                        String currentValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("platformProperty", platformProperty);
        // Текущее значение из application.yml (может быть null — тогда identity придёт из build-info/OTEL_*).
        entry.put("springValue", currentValue == null ? "<unset>" : currentValue);
        entry.put("springProperty", platformProperty);
        hints.put(envVar, entry);
    }

    private static void put(Map<String, Map<String, Object>> hints,
                            String envVar,
                            String otelProperty,
                            String suggestedValue,
                            String springProperty) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("otelProperty", otelProperty);
        entry.put("suggestedValue", suggestedValue);
        entry.put("springProperty", springProperty);
        hints.put(envVar, entry);
    }
}
