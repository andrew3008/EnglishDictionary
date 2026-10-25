package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

final class PlatformTracingSamplerMetricsBinder implements MeterBinder {

    private static final String METRIC_NAME = "platform.tracing.sampler.decisions";
    private static final String DOMAIN = "sampling";
    private static final String OWNER = "sampler";

    private final CoalescingJmxObservationSource<SamplingObservation> samplingObservationSource;
    private final CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource;
    private final MonotonicJmxCounterGroupBridge<SamplerDecisionKey> decisionBridge;

    PlatformTracingSamplerMetricsBinder(PlatformTracingJmxClient client,
                                        CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource) {
        this(client, diagnosticsSource, System::nanoTime);
    }

    PlatformTracingSamplerMetricsBinder(PlatformTracingJmxClient client,
                                        CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource,
                                        MonotonicJmxCounterGroupBridge.Ticker ticker) {
        this.samplingObservationSource = new CoalescingJmxObservationSource<>(
                () -> client.sampling().observation(),
                ticker
        );
        this.diagnosticsSource = diagnosticsSource;
        this.decisionBridge = new MonotonicJmxCounterGroupBridge<>(
                SamplerDecisionKey.class,
                this::decisionSnapshot,
                ticker,
                MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS
        );
    }

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        for (SamplerDecisionKey key : SamplerDecisionKey.values()) {
            FunctionCounter.builder(METRIC_NAME, decisionBridge, bridge -> bridge.count(key))
                    .tags("decision", key.decision().equals("RECORD_AND_SAMPLE") ? "sampled" : "dropped",
                            "reason", key.reason())
                    .description("Number of sampling decisions")
                    .register(registry);

            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.ANOMALY_METRIC_NAME, decisionBridge, bridge -> bridge.anomalyCount(key))
                    .tags("domain", DOMAIN, "owner", OWNER, "metric", key.wireKey())
                    .description("Backward-moving cumulative JMX values observed for a single owner generation")
                    .register(registry);
        }

        for (MonotonicJmxCounterGroupBridge.ResetCause cause : MonotonicJmxCounterGroupBridge.ResetCause.values()) {
            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.RESET_METRIC_NAME, decisionBridge, bridge -> bridge.resetCount(cause))
                    .tags("domain", DOMAIN, "owner", OWNER, "cause", cause.tag())
                    .description("Source identity resets observed for a cumulative JMX counter group")
                    .register(registry);
        }

        FunctionCounter.builder("platform.tracing.sampling.invalid_config", diagnosticsSource,
                        PlatformTracingSamplerMetricsBinder::invalidConfigCount)
                .description("Number of failed configuration update attempts (e.g. invalid ratio)")
                .register(registry);
    }

    private Optional<MonotonicJmxCounterGroupBridge.SourceSnapshot<SamplerDecisionKey>> decisionSnapshot() {
        return samplingObservationSource.get()
                .map(observation -> new MonotonicJmxCounterGroupBridge.SourceSnapshot<>(
                        toEnumKeyedState(observation.decisionCounts()),
                        observation.agentInstanceId()
                ));
    }

    private static JmxOwnerState<Map<SamplerDecisionKey, Long>> toEnumKeyedState(JmxOwnerState<Map<String, Long>> state) {
        if (!(state instanceof JmxOwnerState.Present<Map<String, Long>>(Map<String, Long> values, long generation))) {
            return new JmxOwnerState.Absent<>(state.generation());
        }

        Map<SamplerDecisionKey, Long> keyed = new EnumMap<>(SamplerDecisionKey.class);
        for (SamplerDecisionKey key : SamplerDecisionKey.values()) {
            keyed.put(key, values.getOrDefault(key.wireKey(), 0L));
        }

        return new JmxOwnerState.Present<>(keyed, generation);
    }

    private static double invalidConfigCount(CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource) {
        return diagnosticsSource.get()
                .map(DiagnosticsObservation::invalidConfigCount)
                .orElse(0L)
                .doubleValue();
    }
}
