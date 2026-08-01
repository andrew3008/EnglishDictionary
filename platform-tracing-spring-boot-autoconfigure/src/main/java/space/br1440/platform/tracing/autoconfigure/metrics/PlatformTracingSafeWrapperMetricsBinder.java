package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.Map;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class PlatformTracingSafeWrapperMetricsBinder implements MeterBinder {

    private static final String PREFIX = "platform.tracing.safe_wrapper.";

    private final PlatformTracingJmxClient client;

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        registerCounter(registry, "failures", "sampler", "sampler.failures");
        registerCounter(registry, "failures", "propagator", "propagator.failures");
        registerCounter(registry, "failures", "resource", "resource.failures");
        registerCounter(registry, "failures", "scope", "scope.failures");

        FunctionCounter.builder(PREFIX + "suppressed_errors", client,
                        c -> readMetric(c, "safe_wrapper.suppressed_errors"))
                .description("Total number of errors suppressed by tracing safe wrappers")
                .register(registry);

        Gauge.builder(PREFIX + "degraded_mode", client, c -> readMetric(c, "degraded_mode.enabled"))
                .description("Degraded-mode flag for Sampler/Processor layers (1 — enabled)")
                .register(registry);

        Gauge.builder(PREFIX + "last_failure_epoch_ms", client, c -> readMetric(c, "last_failure.epoch_ms"))
                .description("Timestamp of the last safe-wrapper failure, epoch millis")
                .register(registry);
    }

    @SuppressWarnings("SameParameterValue")
    private void registerCounter(MeterRegistry registry, String suffix, String component, String key) {
        FunctionCounter.builder(PREFIX + suffix, client, c -> readMetric(c, key))
                .tags("component", component)
                .description("Tracing safe-wrapper failures by component type")
                .register(registry);
    }

    private static double readMetric(PlatformTracingJmxClient client, String key) {
        try {
            Map<String, Long> snapshot = client.getSafeWrapperMetrics();
            Long value = snapshot.get(key);
            return (value != null) ? value.doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
