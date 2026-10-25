package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationCaptureEnvelope;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotStore;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

public final class ScrubbingObservationSource {

    private static final long RUNTIME_STATE_ACTIVE = 0L;
    private static final long RUNTIME_STATE_DEGRADED_FAIL_CLOSED = 1L;

    private final OwnerRegistrationSlot<ScrubbingRuntime> scrubbingSlot;
    private final ObservationCaptureEnvelope envelope;
    private final ObservationSnapshotStore snapshotStore = new ObservationSnapshotStore(JmxOpenTypes.SCRUBBING_OBSERVATION);

    public ScrubbingObservationSource(OwnerRegistrationSlot<ScrubbingRuntime> scrubbingSlot,
                                      AgentInstanceId agentInstanceId) {
        this.scrubbingSlot = Objects.requireNonNull(scrubbingSlot, "scrubbingSlot");
        this.envelope = new ObservationCaptureEnvelope(agentInstanceId);
        publishObservationSnapshot();
    }

    public CompositeData getScrubbingObservation() {
        return snapshotStore.current();
    }

    public synchronized void publishObservationSnapshot() {
        OwnerRegistrationState<ScrubbingRuntime> state = scrubbingSlot.capture();

        ScrubbingSnapshot snapshot;
        if (state instanceof OwnerRegistrationState.Present<ScrubbingRuntime> present) {
            snapshot = ScrubbingSnapshot.from(present.owner());
        } else {
            snapshot = ScrubbingSnapshot.absent();
        }

        Map<String, Object> values = new LinkedHashMap<>();
        envelope.putInto(values);
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_PRESENT, snapshot.ownerPresent());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_GENERATION, state.generation());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_ENABLED, snapshot.enabled());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_POLICY_VERSION, snapshot.policyVersion());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_RULES_LOADED, snapshot.rulesLoaded());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_METRICS,
                JmxOpenTypeSupport.stringLongMap(
                        JmxOpenTypes.SCRUBBING_METRICS,
                        JmxWireContract.FIELD_SCRUBBING_METRICS_KEY,
                        JmxWireContract.FIELD_SCRUBBING_METRICS_VALUE,
                        snapshot.metrics()
                )
        );
        snapshotStore.publish(values);
    }

    private record ScrubbingSnapshot(boolean ownerPresent,
                                     boolean enabled,
                                     long policyVersion,
                                     long rulesLoaded,
                                     Map<String, Long> metrics) {

        private static final ScrubbingSnapshot ABSENT = new ScrubbingSnapshot(false, false, 0L, 0L, Map.of());

        static ScrubbingSnapshot absent() {
            return ABSENT;
        }

        static ScrubbingSnapshot from(ScrubbingRuntime runtime) {
            return new ScrubbingSnapshot(
                    true,
                    runtime.enabled(),
                    runtime.policyVersion(),
                    runtime.ruleCount(),
                    snapshotMetrics(runtime)
            );
        }
    }

    private static Map<String, Long> snapshotMetrics(ScrubbingRuntime runtime) {
        boolean attributesVerified = runtime.attributesVerified();

        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_DROP, runtime.droppedActions());
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_HASH, runtime.hashedActions());
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_MASK, runtime.maskedActions());
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_TRUNCATE, runtime.truncatedActions());
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_FAILURES, runtime.failures());
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_HASH_MISSING_KEY, runtime.hashMissingKey());
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_CIRCUIT_BREAKERS_FAILURES_TOTAL, runtime.totalBreakerFailures());
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_SKIPPED_CIRCUIT_OPEN, runtime.skippedCircuitOpen());
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_ATTRIBUTES_VERIFIED, attributesVerified ? 1L : 0L);
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_RUNTIME_STATE,
                attributesVerified ? RUNTIME_STATE_ACTIVE : RUNTIME_STATE_DEGRADED_FAIL_CLOSED);
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_COLLAPSE_MODE_ACTIVATIONS, runtime.collapseModeActivations());
        snapshot.put(JmxWireContract.KEY_SCRUBBING_METRICS_COLLAPSED_SPANS, runtime.collapsedSpans());
        return snapshot;
    }
}
