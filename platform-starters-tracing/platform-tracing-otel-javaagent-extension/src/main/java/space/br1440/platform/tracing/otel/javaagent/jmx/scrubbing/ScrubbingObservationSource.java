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
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
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
        ObservationCaptureEnvelope.OpenCapture openCapture = envelope.open();

        OwnerCapture<ScrubbingRuntime> capture = OwnerCapture.of(scrubbingSlot);
        OwnerRegistrationState<ScrubbingRuntime> state = capture.state();
        long offsetNanos = capture.offsetNanos();

        ScrubbingSnapshot snapshot;
        if (state instanceof OwnerRegistrationState.Present<ScrubbingRuntime> present) {
            snapshot = ScrubbingSnapshot.from(present.owner());
        } else {
            snapshot = ScrubbingSnapshot.absent();
        }

        Map<String, Object> values = new LinkedHashMap<>();
        openCapture.sealInto(values);
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_PRESENT, snapshot.ownerPresent());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_GENERATION, state.generation());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_CAPTURED_OFFSET_NANOS, offsetNanos);
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_ENABLED, snapshot.enabled());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_POLICY_VERSION, snapshot.policyVersion());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_RULES_LOADED, snapshot.rulesLoaded());
        values.put(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_CIRCUIT_BREAKERS_OPEN, snapshot.circuitBreakersOpen());
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
                                     long circuitBreakersOpen,
                                     Map<String, Long> metrics) {

        private static final ScrubbingSnapshot ABSENT =
                new ScrubbingSnapshot(false, false, 0L, 0L, 0L, Map.of());

        static ScrubbingSnapshot absent() {
            return ABSENT;
        }

        static ScrubbingSnapshot from(ScrubbingRuntime runtime) {
            return new ScrubbingSnapshot(
                    true,
                    runtime.enabled(),
                    runtime.policyVersion(),
                    runtime.ruleCount(),
                    runtime.openBreakerCount(),
                    snapshotMetrics(runtime)
            );
        }
    }

    private static Map<String, Long> snapshotMetrics(ScrubbingRuntime runtime) {
        boolean attributesVerified = runtime.attributesVerified();

        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("drop", runtime.droppedActions());
        snapshot.put("hash", runtime.hashedActions());
        snapshot.put("mask", runtime.maskedActions());
        snapshot.put("truncate", runtime.truncatedActions());
        snapshot.put("failures", runtime.failures());
        snapshot.put("hash_missing_key", runtime.hashMissingKey());
        snapshot.put("rules.loaded", runtime.ruleCount());
        snapshot.put("circuit_breakers.open", runtime.openBreakerCount());
        snapshot.put("circuit_breakers.failures.total", runtime.totalBreakerFailures());
        snapshot.put("enabled", runtime.enabled() ? 1L : 0L);
        snapshot.put("policy.version", runtime.policyVersion());
        snapshot.put("attributes_verified", attributesVerified ? 1L : 0L);
        snapshot.put("runtime_state", attributesVerified ? RUNTIME_STATE_ACTIVE : RUNTIME_STATE_DEGRADED_FAIL_CLOSED);
        snapshot.put("collapse_mode_activations", runtime.collapseModeActivations());
        snapshot.put("collapsed_spans", runtime.collapsedSpans());
        return snapshot;
    }
}
