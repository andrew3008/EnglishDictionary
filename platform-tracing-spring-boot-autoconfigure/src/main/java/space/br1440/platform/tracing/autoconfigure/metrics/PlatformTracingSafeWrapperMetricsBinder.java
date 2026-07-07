package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;

import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Интеграция диагностики safe-обёрток (Фаза 11) в Micrometer/Prometheus по polling-модели.
 * Читает атрибут {@code SafeWrapperMetrics} с MBean домена {@code DiagnosticsControl}.
 */
public class PlatformTracingSafeWrapperMetricsBinder implements MeterBinder {

    private static final String PREFIX = "platform.tracing.safe_wrapper.";

    private final PlatformTracingJmxClient client;

    public PlatformTracingSafeWrapperMetricsBinder(PlatformTracingJmxClient client) {
        this.client = client;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registerCounter(registry, "failures", "sampler", "sampler.failures");
        registerCounter(registry, "failures", "propagator", "propagator.failures");
        registerCounter(registry, "failures", "resource", "resource.failures");
        registerCounter(registry, "failures", "scope", "scope.failures");

        FunctionCounter.builder(PREFIX + "suppressed_errors", client,
                        c -> readMetric(c, "safe_wrapper.suppressed_errors"))
                .description("Суммарно подавленных ошибок tracing safe-обёртками")
                .register(registry);

        Gauge.builder(PREFIX + "degraded_mode", client, c -> readMetric(c, "degraded_mode.enabled"))
                .description("Признак degraded-режима слоёв Sampler/Processor (1 — включён)")
                .register(registry);

        Gauge.builder(PREFIX + "last_failure_epoch_ms", client, c -> readMetric(c, "last_failure.epoch_ms"))
                .description("Время последнего сбоя safe-обёртки, epoch millis")
                .register(registry);
    }

    private void registerCounter(MeterRegistry registry, String suffix, String component, String key) {
        FunctionCounter.builder(PREFIX + suffix, client, c -> readMetric(c, key))
                .tags("component", component)
                .description("Отказы safe-обёрток tracing по типу компонента")
                .register(registry);
    }

    private static double readMetric(PlatformTracingJmxClient client, String key) {
        try {
            Map<String, Long> snapshot = client.getSafeWrapperMetrics();
            Long value = snapshot.get(key);
            return value != null ? value.doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    static ToDoubleFunction<PlatformTracingJmxClient> reader(String key) {
        return c -> readMetric(c, key);
    }
}
