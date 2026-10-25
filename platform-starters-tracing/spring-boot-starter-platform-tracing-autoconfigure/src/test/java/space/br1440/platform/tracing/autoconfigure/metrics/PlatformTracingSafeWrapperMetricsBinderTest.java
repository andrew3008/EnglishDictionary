package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PlatformTracingSafeWrapperMetricsBinderTest {

    private static final String AGENT_INSTANCE_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String FAILURES_METRIC = "platform.tracing.safe_wrapper.failures";
    private static final String SUPPRESSED_METRIC = "platform.tracing.safe_wrapper.suppressed_errors";
    private static final String DEGRADED_MODE_METRIC = "platform.tracing.safe_wrapper.degraded_mode";
    private static final String LAST_FAILURE_METRIC = "platform.tracing.safe_wrapper.last_failure_epoch_ms";

    @Test
    void absentObservationCollapsesCountersToZeroAndTriStateGaugesToNaN() {
        SimpleMeterRegistry registry = bindWith(Optional::empty);

        assertThat(failureCounter(registry, "sampler")).isZero();
        assertThat(failureCounter(registry, "propagator")).isZero();
        assertThat(failureCounter(registry, "resource")).isZero();
        assertThat(registry.get(SUPPRESSED_METRIC).functionCounter().count()).isZero();
        assertThat(registry.get(DEGRADED_MODE_METRIC).gauge().value()).isNaN();
        assertThat(registry.get(LAST_FAILURE_METRIC).gauge().value()).isNaN();
    }

    @Test
    void presentObservationReadsComponentFailureCountersByTag() {
        DiagnosticsObservation observation = observationWithSafeWrapperMetrics(Map.of(
                JmxWireContract.KEY_SAFE_WRAPPER_METRICS_SAMPLER_FAILURES, 4L,
                JmxWireContract.KEY_SAFE_WRAPPER_METRICS_PROPAGATOR_FAILURES, 1L,
                JmxWireContract.KEY_SAFE_WRAPPER_METRICS_RESOURCE_FAILURES, 0L
        ));
        SimpleMeterRegistry registry = bindWith(() -> Optional.of(observation));

        assertThat(failureCounter(registry, "sampler")).isEqualTo(4.0);
        assertThat(failureCounter(registry, "propagator")).isEqualTo(1.0);
        assertThat(failureCounter(registry, "resource")).isZero();
    }

    @Test
    void presentObservationDegradedModeGaugeIsHonestZeroNotNaNWhenDisabled() {
        DiagnosticsObservation observation = observationWithSafeWrapperMetrics(
                Map.of(JmxWireContract.KEY_SAFE_WRAPPER_METRICS_DEGRADED_MODE_ENABLED, 0L)
        );
        SimpleMeterRegistry registry = bindWith(() -> Optional.of(observation));

        assertThat(registry.get(DEGRADED_MODE_METRIC).gauge().value()).isZero();
    }

    @Test
    void presentObservationDegradedModeGaugeReflectsEnabledFlagAndLastFailureTimestamp() {
        DiagnosticsObservation observation = observationWithSafeWrapperMetrics(
                Map.of(
                        JmxWireContract.KEY_SAFE_WRAPPER_METRICS_DEGRADED_MODE_ENABLED, 1L,
                        JmxWireContract.KEY_SAFE_WRAPPER_METRICS_LAST_FAILURE_EPOCH_MS, 1_700_000_000_000L
                )
        );
        SimpleMeterRegistry registry = bindWith(() -> Optional.of(observation));

        assertThat(registry.get(DEGRADED_MODE_METRIC).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(LAST_FAILURE_METRIC).gauge().value()).isEqualTo(1_700_000_000_000.0);
    }

    @Test
    void missingKeyInPresentObservationDefaultsSuppressedErrorsCounterToZero() {
        DiagnosticsObservation observation = observationWithSafeWrapperMetrics(Map.of());
        SimpleMeterRegistry registry = bindWith(() -> Optional.of(observation));

        assertThat(registry.get(SUPPRESSED_METRIC).functionCounter().count()).isZero();
        assertThat(registry.get(LAST_FAILURE_METRIC).gauge().value()).isZero();
    }

    private static DiagnosticsObservation observationWithSafeWrapperMetrics(Map<String, Long> safeWrapperMetrics) {
        return new DiagnosticsObservation(AGENT_INSTANCE_ID, 0L, safeWrapperMetrics);
    }

    private SimpleMeterRegistry bindWith(Supplier<Optional<DiagnosticsObservation>> supplier) {
        AtomicLong nanoTime = new AtomicLong(0);
        CoalescingJmxObservationSource<DiagnosticsObservation> source = new CoalescingJmxObservationSource<>(supplier, nanoTime::get, 0L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformTracingSafeWrapperMetricsBinder(source).bindTo(registry);
        return registry;
    }

    private static double failureCounter(SimpleMeterRegistry registry, String component) {
        return registry.get(FAILURES_METRIC).tag("component", component).functionCounter().count();
    }
}
