package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import jakarta.annotation.Nonnull;

import lombok.RequiredArgsConstructor;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;

import java.util.Map;

@RequiredArgsConstructor
final class PlatformTracingConfigMetricsBinder implements MeterBinder {

    private static final String PREFIX = "platform.tracing.config.";

    private final PlatformTracingJmxClient client;

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        FunctionCounter.builder(PREFIX + "updates", client, c -> readMetric(c, "updates.applied"))
                .tags("result", "applied")
                .description("Applied runtime tracing configuration updates")
                .register(registry);

        FunctionCounter.builder(PREFIX + "updates", client, c -> readMetric(c, "updates.rejected"))
                .tags("result", "rejected")
                .description("Rejected runtime configuration updates (last-known-good retained)")
                .register(registry);

        Gauge.builder(PREFIX + "last_update_epoch_ms", client, c -> readMetric(c, "last_update.epoch_ms"))
                .description("Timestamp of the last dynamic configuration change, epoch millis")
                .register(registry);

        Gauge.builder(PREFIX + "sampling_version", client,
                        c -> c.getSamplingConfigVersion().map(Long::doubleValue).orElse(0.0))
                .description("Version of the active sampler configuration snapshot")
                .register(registry);
    }

    private static double readMetric(PlatformTracingJmxClient client, String key) {
        try {
            Map<String, Long> snapshot = client.getConfigReloadMetrics();
            Long value = snapshot.get(key);
            return (value != null) ? value.doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
