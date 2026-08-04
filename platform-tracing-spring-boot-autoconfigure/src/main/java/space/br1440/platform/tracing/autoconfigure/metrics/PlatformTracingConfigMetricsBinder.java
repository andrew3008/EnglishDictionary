package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import jakarta.annotation.Nonnull;

import lombok.RequiredArgsConstructor;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;

import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
final class PlatformTracingConfigMetricsBinder implements MeterBinder {

    private static final String PREFIX = "platform.tracing.config.";

    private final CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource;

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        FunctionCounter.builder(PREFIX + "updates", diagnosticsSource, source -> readMetric(source, "updates.applied"))
                .tags("result", "applied")
                .description("Applied runtime tracing configuration updates")
                .register(registry);

        FunctionCounter.builder(PREFIX + "updates", diagnosticsSource, source -> readMetric(source, "updates.rejected"))
                .tags("result", "rejected")
                .description("Rejected runtime configuration updates (last-known-good retained)")
                .register(registry);

        Gauge.builder(PREFIX + "last_update_epoch_ms", diagnosticsSource,
                        source -> readTimestampMetric(source, "last_update.epoch_ms"))
                .description("Timestamp of the last dynamic configuration change, epoch millis")
                .register(registry);
    }

    private static double readMetric(CoalescingJmxObservationSource<DiagnosticsObservation> source, String key) {
        Optional<DiagnosticsObservation> observation = source.get();
        Map<String, Long> snapshot = observation.map(DiagnosticsObservation::configReloadMetrics).orElseGet(Map::of);
        Long value = snapshot.get(key);
        return (value != null) ? value.doubleValue() : 0.0;
    }

    private static double readTimestampMetric(CoalescingJmxObservationSource<DiagnosticsObservation> source, String key) {
        Optional<DiagnosticsObservation> observation = source.get();
        if (observation.isEmpty()) {
            return Double.NaN;
        }

        Long value = observation.get().configReloadMetrics().get(key);
        return (value != null) ? value.doubleValue() : 0.0;
    }
}
