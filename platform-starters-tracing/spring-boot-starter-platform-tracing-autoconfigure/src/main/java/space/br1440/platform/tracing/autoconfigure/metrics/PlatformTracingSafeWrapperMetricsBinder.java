package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.Map;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.metrics.utils.CoalescingObservationMetricReader;

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
        registerCounter(
                registry, "failures", "sampler",
                JmxWireContract.KEY_SAFE_WRAPPER_METRICS_SAMPLER_FAILURES
        );

        registerCounter(
                registry, "failures", "propagator",
                JmxWireContract.KEY_SAFE_WRAPPER_METRICS_PROPAGATOR_FAILURES
        );

        registerCounter(
                registry, "failures", "resource",
                JmxWireContract.KEY_SAFE_WRAPPER_METRICS_RESOURCE_FAILURES
        );

        FunctionCounter.builder(PREFIX + "suppressed_errors", diagnosticsSource, PlatformTracingSafeWrapperMetricsBinder::suppressedErrors)
                .description("Total number of errors suppressed by tracing safe wrappers")
                .register(registry);

        Gauge.builder(PREFIX + "degraded_mode", diagnosticsSource, PlatformTracingSafeWrapperMetricsBinder::degradedMode)
                .description("Degraded-mode flag for Sampler/Processor layers (1 — enabled, NaN — unknown)")
                .register(registry);

        Gauge.builder(PREFIX + "last_failure_epoch_ms", diagnosticsSource, PlatformTracingSafeWrapperMetricsBinder::lastFailureEpochMs)
                .description("Timestamp of the last safe-wrapper failure, epoch millis")
                .register(registry);
    }

    @SuppressWarnings("SameParameterValue")
    private void registerCounter(MeterRegistry registry, String suffix, String component, String key) {
        FunctionCounter.builder(PREFIX + suffix, diagnosticsSource,
                        source -> CoalescingObservationMetricReader.readMetric(safeWrapperMetrics(source), key))
                .tags("component", component)
                .description("Tracing safe-wrapper failures by component type")
                .register(registry);
    }

    private static double suppressedErrors(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        Map<String, Long> metrics = safeWrapperMetrics(source);
        return CoalescingObservationMetricReader.readMetric(
                metrics,
                JmxWireContract.KEY_SAFE_WRAPPER_METRICS_SAFE_WRAPPER_SUPPRESSED_ERRORS
        );
    }

    private static double degradedMode(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        Map<String, Long> metrics = safeWrapperMetricsOrAbsent(source);
        return CoalescingObservationMetricReader.readTriStateMetric(
                metrics,
                JmxWireContract.KEY_SAFE_WRAPPER_METRICS_DEGRADED_MODE_ENABLED
        );
    }

    private static double lastFailureEpochMs(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        Map<String, Long> metrics = safeWrapperMetricsOrAbsent(source);
        return CoalescingObservationMetricReader.readTriStateMetric(
                metrics,
                JmxWireContract.KEY_SAFE_WRAPPER_METRICS_LAST_FAILURE_EPOCH_MS
        );
    }

    private static Map<String, Long> safeWrapperMetrics(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        return source.get()
                .map(DiagnosticsObservation::safeWrapperMetrics)
                .orElseGet(Map::of);
    }

    @Nullable
    private static Map<String, Long> safeWrapperMetricsOrAbsent(CoalescingJmxObservationSource<DiagnosticsObservation> source) {
        return source.get()
                .map(DiagnosticsObservation::safeWrapperMetrics)
                .orElse(null);
    }
}
