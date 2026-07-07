package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;

import java.util.Map;

/**
 * Интеграция наблюдаемости перезагрузки динамической конфигурации (Фаза 14) в Micrometer/Prometheus
 * по polling-модели. Читает атрибут {@code ConfigReloadMetrics} с MBean домена
 * {@code DiagnosticsControl}.
 */
public class PlatformTracingConfigMetricsBinder implements MeterBinder {

    private static final String PREFIX = "platform.tracing.config.";

    private final PlatformTracingJmxClient client;

    public PlatformTracingConfigMetricsBinder(PlatformTracingJmxClient client) {
        this.client = client;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder(PREFIX + "updates", client, c -> readMetric(c, "updates.applied"))
                .tags("result", "applied")
                .description("Применённые runtime-обновления конфигурации трассировки")
                .register(registry);
        FunctionCounter.builder(PREFIX + "updates", client, c -> readMetric(c, "updates.rejected"))
                .tags("result", "rejected")
                .description("Отклонённые runtime-обновления конфигурации (сохранён last-known-good)")
                .register(registry);

        Gauge.builder(PREFIX + "last_update_epoch_ms", client, c -> readMetric(c, "last_update.epoch_ms"))
                .description("Время последнего изменения динамической конфигурации, epoch millis")
                .register(registry);

        Gauge.builder(PREFIX + "sampling_version", client,
                        c -> c.getSamplingConfigVersion().map(Long::doubleValue).orElse(0.0))
                .description("Версия активного снимка конфигурации сэмплера")
                .register(registry);
    }

    private static double readMetric(PlatformTracingJmxClient client, String key) {
        try {
            Map<String, Long> snapshot = client.getConfigReloadMetrics();
            Long value = snapshot.get(key);
            return value != null ? value.doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
