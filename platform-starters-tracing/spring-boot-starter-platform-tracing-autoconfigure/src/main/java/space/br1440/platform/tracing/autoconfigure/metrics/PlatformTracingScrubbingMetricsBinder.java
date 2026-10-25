package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

final class PlatformTracingScrubbingMetricsBinder implements MeterBinder {

    private static final String METRIC_NAME = "platform.tracing.scrubbing.count";
    private static final String DOMAIN = "scrubbing";
    private static final String OWNER = "scrubbing";

    private static final String RUNTIME_STATE_METRIC_NAME = "platform.tracing.scrubbing.runtime.state";
    private static final String ATTRIBUTES_VERIFIED_METRIC_NAME = "platform.tracing.scrubbing.attributes.verified";

    private final CoalescingJmxObservationSource<ScrubbingObservation> observationSource;
    private final MonotonicJmxCounterGroupBridge<ScrubbingMetricKey> bridge;

    PlatformTracingScrubbingMetricsBinder(PlatformTracingJmxClient client) {
        this(client, System::nanoTime);
    }

    PlatformTracingScrubbingMetricsBinder(PlatformTracingJmxClient client, MonotonicJmxCounterGroupBridge.Ticker ticker) {
        this.observationSource = new CoalescingJmxObservationSource<>(
                () -> client.scrubbing().observation(),
                ticker
        );

        this.bridge = new MonotonicJmxCounterGroupBridge<>(
                ScrubbingMetricKey.class,
                this::counterSnapshot,
                ticker,
                MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS
        );
    }

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        for (ScrubbingMetricKey key : ScrubbingMetricKey.values()) {
            FunctionCounter.builder(METRIC_NAME, bridge, b -> b.count(key))
                    .tags("metric", key.wireKey())
                    .description("Cumulative counters of the scrubbing span processor owner")
                    .register(registry);

            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.ANOMALY_METRIC_NAME, bridge, b -> b.anomalyCount(key))
                    .tags("domain", DOMAIN, "owner", OWNER, "metric", key.wireKey())
                    .description("Backward-moving cumulative JMX values observed for a single owner generation")
                    .register(registry);
        }

        for (ScrubbingRuntimeState state : ScrubbingRuntimeState.values()) {
            Gauge.builder(RUNTIME_STATE_METRIC_NAME, this, binder -> binder.runtimeStateValue(state))
                    .tags("state", state.tag())
                    .description("Current scrubbing runtime state; 1 for the active state, 0 otherwise")
                    .register(registry);
        }

        Gauge.builder(ATTRIBUTES_VERIFIED_METRIC_NAME, this, PlatformTracingScrubbingMetricsBinder::attributesVerifiedValue)
                .description("1 when span attributes are sanitized by the processor, 0 when the export boundary collapses them")
                .register(registry);

        for (MonotonicJmxCounterGroupBridge.ResetCause cause : MonotonicJmxCounterGroupBridge.ResetCause.values()) {
            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.RESET_METRIC_NAME, bridge, b -> b.resetCount(cause))
                    .tags("domain", DOMAIN, "owner", OWNER, "cause", cause.tag())
                    .description("Source identity resets observed for a cumulative JMX counter group")
                    .register(registry);
        }
    }

    private Optional<MonotonicJmxCounterGroupBridge.SourceSnapshot<ScrubbingMetricKey>> counterSnapshot() {
        return observationSource.get()
                .map(observation -> new MonotonicJmxCounterGroupBridge.SourceSnapshot<>(
                        toEnumKeyedState(observation.owner()),
                        observation.agentInstanceId()
                ));
    }

    private double runtimeStateValue(ScrubbingRuntimeState state) {
        return runtimeStateValue(state, attributesVerifiedValue());
    }

    static double runtimeStateValue(ScrubbingRuntimeState state, double attributesVerified) {
        if (Double.isNaN(attributesVerified)) {
            return Double.NaN;
        }

        boolean degraded = (attributesVerified == 0.0d);
        boolean matches = (state == ScrubbingRuntimeState.DEGRADED_FAIL_CLOSED) == degraded;
        return matches ? 1.0d : 0.0d;
    }

    private double attributesVerifiedValue() {
        return currentValues()
                .map(values -> values.metrics().get(JmxWireContract.KEY_SCRUBBING_METRICS_ATTRIBUTES_VERIFIED))
                .map(Long::doubleValue)
                .orElse(Double.NaN);
    }

    private Optional<ScrubbingValues> currentValues() {
        return observationSource.get()
                .map(ScrubbingObservation::owner)
                .flatMap(state -> (state instanceof JmxOwnerState.Present<ScrubbingValues> present)
                        ? Optional.of(present.values())
                        : Optional.empty());
    }

    static JmxOwnerState<Map<ScrubbingMetricKey, Long>> toEnumKeyedState(JmxOwnerState<ScrubbingValues> state) {
        if (!(state instanceof JmxOwnerState.Present<ScrubbingValues>(ScrubbingValues values, long generation))) {
            return new JmxOwnerState.Absent<>(state.generation());
        }

        Map<String, Long> wireCounts = values.metrics();
        Map<ScrubbingMetricKey, Long> keyed = new EnumMap<>(ScrubbingMetricKey.class);
        for (ScrubbingMetricKey key : ScrubbingMetricKey.values()) {
            keyed.put(key, wireCounts.getOrDefault(key.wireKey(), 0L));
        }

        return new JmxOwnerState.Present<>(keyed, generation);
    }
}
