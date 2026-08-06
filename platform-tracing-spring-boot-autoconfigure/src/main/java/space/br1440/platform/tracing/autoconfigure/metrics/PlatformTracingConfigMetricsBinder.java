package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.Map;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.metrics.utils.CoalescingObservationMetricReader;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class PlatformTracingConfigMetricsBinder implements MeterBinder {

    private static final String PREFIX = "platform.tracing.config.";

    private final CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource;

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        FunctionCounter.builder(PREFIX + "updates", diagnosticsSource, PlatformTracingConfigMetricsBinder::appliedUpdates)
                .tags("result", "applied")
                .description("Applied runtime tracing configuration updates")
                .register(registry);

        FunctionCounter.builder(PREFIX + "updates", diagnosticsSource, PlatformTracingConfigMetricsBinder::rejectedUpdates)
                .tags("result", "rejected")
                .description("Rejected runtime configuration updates (last-known-good retained)")
                .register(registry);

        Gauge.builder(PREFIX + "last_update_epoch_ms", diagnosticsSource, PlatformTracingConfigMetricsBinder::lastUpdateEpochMs)
                .description("Timestamp of the last dynamic configuration change, epoch millis")
                .register(registry);
    }

    private static double appliedUpdates(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        Map<String, Long> metrics = configReloadMetrics(source);
        return CoalescingObservationMetricReader.readMetric(metrics, "updates.applied");
    }

    private static double rejectedUpdates(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        Map<String, Long> metrics = configReloadMetrics(source);
        return CoalescingObservationMetricReader.readMetric(metrics, "updates.rejected");
    }

    private static double lastUpdateEpochMs(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        Map<String, Long> metrics = configReloadMetricsOrAbsent(source);
        return CoalescingObservationMetricReader.readTriStateMetric(metrics, "last_update.epoch_ms");
    }

    private static Map<String, Long> configReloadMetrics(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        return source.get()
                .map(DiagnosticsObservation::configReloadMetrics)
                .orElseGet(Map::of);
    }

    @Nullable
    private static Map<String, Long> configReloadMetricsOrAbsent(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        return source.get()
                .map(DiagnosticsObservation::configReloadMetrics)
                .orElse(null);
    }
}
