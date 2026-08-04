package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class PlatformTracingSafeWrapperMetricsBinder implements MeterBinder {

    private static final String PREFIX = "platform.tracing.safe_wrapper.";

    private final CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource;

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        registerCounter(registry, "failures", "sampler", "sampler.failures");
        registerCounter(registry, "failures", "propagator", "propagator.failures");
        registerCounter(registry, "failures", "resource", "resource.failures");
        registerCounter(registry, "failures", "scope", "scope.failures");

        FunctionCounter.builder(PREFIX + "suppressed_errors", diagnosticsSource,
                        source -> readMetric(source, "safe_wrapper.suppressed_errors"))
                .description("Total number of errors suppressed by tracing safe wrappers")
                .register(registry);

        Gauge.builder(PREFIX + "degraded_mode", diagnosticsSource, source -> readTriStateMetric(source, "degraded_mode.enabled"))
                .description("Degraded-mode flag for Sampler/Processor layers (1 — enabled, NaN — unknown)")
                .register(registry);

        Gauge.builder(PREFIX + "last_failure_epoch_ms", diagnosticsSource,
                        source -> readTriStateMetric(source, "last_failure.epoch_ms"))
                .description("Timestamp of the last safe-wrapper failure, epoch millis")
                .register(registry);
    }

    @SuppressWarnings("SameParameterValue")
    private void registerCounter(MeterRegistry registry, String suffix, String component, String key) {
        FunctionCounter.builder(PREFIX + suffix, diagnosticsSource, source -> readMetric(source, key))
                .tags("component", component)
                .description("Tracing safe-wrapper failures by component type")
                .register(registry);
    }

    private static double readMetric(CoalescingJmxObservationSource<DiagnosticsObservation> source, String key) {
        Optional<DiagnosticsObservation> observation = source.get();
        Map<String, Long> snapshot = observation.map(DiagnosticsObservation::safeWrapperMetrics).orElseGet(Map::of);
        Long value = snapshot.get(key);
        return (value != null) ? value.doubleValue() : 0.0;
    }

    private static double readTriStateMetric(CoalescingJmxObservationSource<DiagnosticsObservation> source, String key) {
        Optional<DiagnosticsObservation> observation = source.get();
        if (observation.isEmpty()) {
            return Double.NaN;
        }

        Long value = observation.get().safeWrapperMetrics().get(key);
        return (value != null) ? value.doubleValue() : 0.0;
    }
}
