package space.br1440.platform.tracing.autoconfigure.actuator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.support.DurationToMillis;

import java.util.Map;
import java.util.function.Function;

/**
 * Стартовая диагностика расхождения двух независимых каналов конфигурации трассировки —
 * Spring {@link TracingProperties} (UX-facing) и OTel Agent extension (SDK policy).
 * <p>
 * Выводит WARN ровно один раз на старте singleton-бинов, если по одной из whitelist
 * shared properties выполнены ВСЕ три условия:
 * <ol>
 *   <li>{@code effective.source != "default-platform"} — оператор реально задал override
 *       через {@code OTEL_*} env / {@code -Dotel.*} system-property;</li>
 *   <li>{@code effective.value != springValue} — значения двух каналов фактически разные;</li>
 *   <li>property входит в whitelist shared values (BSP queue + span limits; default alignment
 *       проверяется {@code SharedDefaultsAlignmentTest}, override drift — здесь).</li>
 * </ol>
 * <p>
 * <b>WARN does not mean application misconfiguration.</b> WARN означает только, что два
 * независимых канала конфигурации показывают разные значения. Это нормально для dual-channel
 * контракта v0.1 (см. {@code ADR-dual-channel-properties-v0.1.md}). Сообщение помогает SRE
 * быстро увидеть расхождение через одно место — startup лог + {@code /actuator/tracing}.
 * <p>
 * Управляется флагом {@code platform.tracing.diagnostics.dual-channel-warn} (default {@code true}).
 *
 * <h2>Почему не fail-fast</h2>
 * Spring-facing и Agent-facing properties не обязаны быть 1:1. У них разные lifecycle
 * (Spring context vs JVM startup) и разные owners (developer vs platform SRE). Жёсткий
 * drift-check породит ложную строгость. См. ADR (рассмотренный и отвергнутый вариант B).
 */
public final class DualChannelDriftDiagnostics implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DualChannelDriftDiagnostics.class);

    /** Не упоминать в сообщении: WARN ≠ misconfiguration. */
    private static final String NOT_A_MISCONFIG_MARKER =
            "WARN does not mean application misconfiguration. "
                    + "It means two independent config channels (Spring vs OTel Agent) differ.";

    private final TracingProperties properties;
    private final OtelEffectiveConfigSnapshot snapshot;
    private final boolean enabled;

    /**
     * Production-конструктор: использует реальные System properties / env vars через
     * default {@link OtelEffectiveConfigSnapshot}.
     */
    public DualChannelDriftDiagnostics(TracingProperties properties, boolean enabled) {
        this(properties, new OtelEffectiveConfigSnapshot(), enabled);
    }

    /**
     * Конструктор с явным snapshot — для unit-тестов и для повторного использования
     * уже сконфигурированного снапшота из контекста (если есть).
     */
    DualChannelDriftDiagnostics(TracingProperties properties,
                                OtelEffectiveConfigSnapshot snapshot,
                                boolean enabled) {
        this.properties = properties;
        this.snapshot = snapshot;
        this.enabled = enabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!enabled) {
            return;
        }
        Map<String, Map<String, Object>> effective = snapshot.build();
        for (SharedProperty shared : whitelist()) {
            Map<String, Object> entry = effective.get(shared.otelProperty);
            if (entry == null) {
                // Свойство не входит в EXPOSED_PROPERTIES снапшота — пропускаем (не наш кейс).
                continue;
            }
            String source = String.valueOf(entry.get("source"));
            if ("default-platform".equals(source) || "default-otel-sdk".equals(source)) {
                // Условие 1 не выполнено: override отсутствует, расхождение невозможно по построению.
                continue;
            }
            Object effectiveValue = entry.get("value");
            if (effectiveValue == null) {
                continue;
            }
            String springValue = shared.springValueSupplier.apply(properties);
            if (springValue == null) {
                continue;
            }
            if (springValue.equals(String.valueOf(effectiveValue))) {
                // Условие 2 не выполнено: значения совпадают, drift отсутствует.
                continue;
            }
            // Все три условия выполнены — логируем WARN ровно один раз для этой property.
            log.warn(
                    "Dual-channel drift: Spring {}={} vs OTel agent effective {}={} (source={}). "
                            + "Spring-сторона не пробрасывается в Agent автоматически (см. ADR-dual-channel-properties-v0.1). "
                            + "Если требуется одинаковое значение — выровняйте через {}={} (см. otelEnvHints в /actuator/tracing). "
                            + NOT_A_MISCONFIG_MARKER,
                    shared.springProperty, springValue,
                    shared.otelProperty, effectiveValue, source,
                    shared.envVarName, springValue
            );
        }
    }

    /**
     * Whitelist shared properties (BSP queue + span limits). Default alignment — {@code SharedDefaultsAlignmentTest}.
     * Универсальный drift по всем properties — намеренно НЕ реализуется (см. ADR).
     */
    private static java.util.List<SharedProperty> whitelist() {
        java.util.List<SharedProperty> list = new java.util.ArrayList<>();
        list.add(new SharedProperty(
                "platform.tracing.queue.max-size",
                "otel.bsp.max.queue.size",
                "OTEL_BSP_MAX_QUEUE_SIZE",
                p -> String.valueOf(p.getQueue().getMaxSize())));
        list.add(new SharedProperty(
                "platform.tracing.queue.export-batch-size",
                "otel.bsp.max.export.batch.size",
                "OTEL_BSP_MAX_EXPORT_BATCH_SIZE",
                p -> String.valueOf(p.getQueue().getExportBatchSize())));
        list.add(new SharedProperty(
                "platform.tracing.queue.export-timeout",
                "otel.bsp.export.timeout",
                "OTEL_BSP_EXPORT_TIMEOUT",
                p -> DurationToMillis.toOtelString(p.getQueue().getExportTimeout())));
        list.add(new SharedProperty(
                "platform.tracing.limits.max-attributes",
                "otel.span.attribute.count.limit",
                "OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT",
                p -> String.valueOf(p.getLimits().getMaxAttributes())));
        list.add(new SharedProperty(
                "platform.tracing.limits.max-attribute-value-length",
                "otel.span.attribute.value.length.limit",
                "OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT",
                p -> String.valueOf(p.getLimits().getMaxAttributeValueLength())));
        list.add(new SharedProperty(
                "platform.tracing.limits.max-events",
                "otel.span.event.count.limit",
                "OTEL_SPAN_EVENT_COUNT_LIMIT",
                p -> String.valueOf(p.getLimits().getMaxEvents())));
        return list;
    }

    /**
     * Описание одной пары Spring ↔ Agent property для diagnostics.
     */
    private static final class SharedProperty {
        final String springProperty;
        final String otelProperty;
        final String envVarName;
        final Function<TracingProperties, String> springValueSupplier;

        SharedProperty(String springProperty,
                       String otelProperty,
                       String envVarName,
                       Function<TracingProperties, String> springValueSupplier) {
            this.springProperty = springProperty;
            this.otelProperty = otelProperty;
            this.envVarName = envVarName;
            this.springValueSupplier = springValueSupplier;
        }
    }

    /**
     * Фабричный метод для unit-тестов: позволяет подменить System property / env var источники
     * детерминированными функциями.
     */
    static DualChannelDriftDiagnostics forTest(TracingProperties properties,
                                               Function<String, String> sysProps,
                                               Function<String, String> envVars,
                                               boolean enabled) {
        OtelEffectiveConfigSnapshot custom = new OtelEffectiveConfigSnapshot(sysProps, envVars);
        return new DualChannelDriftDiagnostics(properties, custom, enabled);
    }
}
