package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

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
    private static final String ATTRIBUTES_VERIFIED_WIRE_KEY = "attributes_verified";

    private final PlatformTracingJmxClient client;
    private final MonotonicJmxCounterGroupBridge<ScrubbingMetricKey> bridge;
    private final MonotonicJmxCounterGroupBridge.Ticker ticker;

    private final AtomicReference<AttributesVerifiedSample> attributesVerified = new AtomicReference<>();

    PlatformTracingScrubbingMetricsBinder(PlatformTracingJmxClient client) {
        this(client, System::nanoTime);
    }

    PlatformTracingScrubbingMetricsBinder(PlatformTracingJmxClient client, MonotonicJmxCounterGroupBridge.Ticker ticker) {
        this.client = client;
        this.ticker = ticker;
        this.bridge = new MonotonicJmxCounterGroupBridge<>(
                ScrubbingMetricKey.class, this::counts, this::scrubbingAgentInstanceId, ticker,
                MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS
        );
    }

    private record AttributesVerifiedSample(long readAtNanos, double value) {
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

    private Optional<JmxOwnerState<Map<ScrubbingMetricKey, Long>>> counts() {
        return client.scrubbing().observation()
                .map(ScrubbingObservation::owner)
                .map(PlatformTracingScrubbingMetricsBinder::toEnumKeyedState);
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
        long now = ticker.nanoTime();

        AttributesVerifiedSample cached = attributesVerified.get();
        if (cached != null
                && (now - cached.readAtNanos()) < MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS) {
            return cached.value();
        }

        double observed = readAttributesVerified();
        attributesVerified.set(new AttributesVerifiedSample(now, observed));
        return observed;
    }

    private double readAttributesVerified() {
        return currentValues()
                .map(values -> values.metrics().get(ATTRIBUTES_VERIFIED_WIRE_KEY))
                .map(Long::doubleValue)
                .orElse(Double.NaN);
    }

    private Optional<ScrubbingValues> currentValues() {
        return client.scrubbing().observation()
                .map(ScrubbingObservation::owner)
                .flatMap(state -> (state instanceof JmxOwnerState.Present<ScrubbingValues> present)
                        ? Optional.of(present.values())
                        : Optional.empty());
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
