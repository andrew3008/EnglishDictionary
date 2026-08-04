package space.br1440.platform.tracing.autoconfigure.metrics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTracingConfigMetricsBinderTest {

    private static final String UPDATES_METRIC = "platform.tracing.config.updates";
    private static final String LAST_UPDATE_METRIC = "platform.tracing.config.last_update_epoch_ms";

    private CoalescingJmxObservationSource<DiagnosticsObservation> source;

    @Test
    void absentObservationCollapsesCountersToZeroAndTimestampToNaN() {
        SimpleMeterRegistry registry = bindWith(() -> Optional.empty());

        assertThat(counter(registry, UPDATES_METRIC, "applied")).isZero();
        assertThat(counter(registry, UPDATES_METRIC, "rejected")).isZero();
        assertThat(registry.get(LAST_UPDATE_METRIC).gauge().value()).isNaN();
    }

    @Test
    void presentObservationReadsKnownKeysAsIs() {
        DiagnosticsObservation observation = observationWithConfigMetrics(
                Map.of("updates.applied", 7L, "updates.rejected", 2L, "last_update.epoch_ms", 1_700_000_000_000L)
        );
        SimpleMeterRegistry registry = bindWith(() -> Optional.of(observation));

        assertThat(counter(registry, UPDATES_METRIC, "applied")).isEqualTo(7.0);
        assertThat(counter(registry, UPDATES_METRIC, "rejected")).isEqualTo(2.0);
        assertThat(registry.get(LAST_UPDATE_METRIC).gauge().value()).isEqualTo(1_700_000_000_000.0);
    }

    @Test
    void missingKeyInPresentObservationDefaultsCounterToZeroNotNaN() {
        DiagnosticsObservation observation = observationWithConfigMetrics(Map.of("updates.applied", 1L));
        SimpleMeterRegistry registry = bindWith(() -> Optional.of(observation));

        assertThat(counter(registry, UPDATES_METRIC, "rejected")).isZero();
        assertThat(registry.get(LAST_UPDATE_METRIC).gauge().value()).isZero();
    }

    @Test
    void functionCounterReflectsLatestSnapshotWithoutAccumulation() {
        AtomicReference<Optional<DiagnosticsObservation>> current =
                new AtomicReference<>(Optional.of(observationWithConfigMetrics(Map.of("updates.applied", 3L))));
        SimpleMeterRegistry registry = bindWith(current::get);
        assertThat(counter(registry, UPDATES_METRIC, "applied")).isEqualTo(3.0);

        current.set(Optional.of(observationWithConfigMetrics(Map.of("updates.applied", 1L))));

        assertThat(counter(registry, UPDATES_METRIC, "applied")).isEqualTo(1.0);
    }

    private static DiagnosticsObservation observationWithConfigMetrics(Map<String, Long> configReloadMetrics) {
        return new DiagnosticsObservation("agent-1", 0L, configReloadMetrics, List.of(), Map.of(), "INFO", true);
    }

    private SimpleMeterRegistry bindWith(Supplier<Optional<DiagnosticsObservation>> supplier) {
        AtomicLong nanoTime = new AtomicLong(0);
        source = new CoalescingJmxObservationSource<>(supplier, nanoTime::get, 0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformTracingConfigMetricsBinder(source).bindTo(registry);
        return registry;
    }

    private static double counter(SimpleMeterRegistry registry, String name, String result) {
        return registry.get(name).tag("result", result).functionCounter().count();
    }
}
