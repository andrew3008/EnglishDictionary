package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

final class PlatformTracingScrubbingMetricsBinder implements MeterBinder {

    private static final String METRIC_NAME = "platform.tracing.scrubbing.count";
    private static final String DOMAIN = "scrubbing";
    private static final String OWNER = "scrubbing";

    private final PlatformTracingJmxClient client;
    private final MonotonicJmxCounterGroupBridge<ScrubbingMetricKey> bridge;

    PlatformTracingScrubbingMetricsBinder(PlatformTracingJmxClient client) {
        this(client, System::nanoTime);
    }

    PlatformTracingScrubbingMetricsBinder(PlatformTracingJmxClient client, MonotonicJmxCounterGroupBridge.Ticker ticker) {
        this.client = client;
        this.bridge = new MonotonicJmxCounterGroupBridge<>(
                ScrubbingMetricKey.class, this::counts, this::scrubbingAgentInstanceId, ticker,
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

        for (MonotonicJmxCounterGroupBridge.ResetCause cause : MonotonicJmxCounterGroupBridge.ResetCause.values()) {
            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.RESET_METRIC_NAME, bridge, b -> b.resetCount(cause))
                    .tags("domain", DOMAIN, "owner", OWNER, "cause", cause.tag())
                    .description("Source identity resets observed for a cumulative JMX counter group")
                    .register(registry);
        }
    }

    private Optional<JmxOwnerState<Map<ScrubbingMetricKey, Long>>> counts() {
        return client.scrubbing().observation()
                .map(ScrubbingObservation::owner)
                .map(PlatformTracingScrubbingMetricsBinder::toEnumKeyedState);
    }

    private String scrubbingAgentInstanceId() {
        return client.scrubbing().observation()
                .map(ScrubbingObservation::agentInstanceId)
                .orElseGet(MonotonicJmxCounterGroupBridge.LOCAL_PROCESS_IDENTITY);
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
