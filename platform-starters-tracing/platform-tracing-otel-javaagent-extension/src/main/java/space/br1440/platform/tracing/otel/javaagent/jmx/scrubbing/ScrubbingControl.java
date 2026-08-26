package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

public final class ScrubbingControl implements ScrubbingControlMBean, ObservationSnapshotControl {

    private static final long RUNTIME_STATE_ACTIVE = 0L;
    private static final long RUNTIME_STATE_DEGRADED_FAIL_CLOSED = 1L;

    private final OwnerRegistrationSlot<ScrubbingRuntime> scrubbingSlot;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();
    private final AtomicReference<CompositeData> published = new AtomicReference<>();

    public ScrubbingControl(OwnerRegistrationSlot<ScrubbingRuntime> scrubbingSlot, UUID agentInstanceId) {
        this.scrubbingSlot = Objects.requireNonNull(scrubbingSlot, "scrubbingSlot");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        publishObservationSnapshot();
    }

    @Override
    public CompositeData getScrubbingObservation() {
        return published.get();
    }

    @Override
    public synchronized void publishObservationSnapshot() {
        long sequence = captureSequence.incrementAndGet();
        long startedAt = Instant.now().toEpochMilli();

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
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCHEMA_VERSION,
                GeneratedJmxWireContract.OBSERVATION_SCHEMA_VERSION);
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_SEQUENCE, sequence);
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_AGENT_INSTANCE_ID,
                agentInstanceId.toString());
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_STARTED_AT, startedAt);
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_COMPLETED_AT,
                Instant.now().toEpochMilli());
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_PRESENT, snapshot.ownerPresent());
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_GENERATION, state.generation());
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_CAPTURED_OFFSET_NANOS, offsetNanos);
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_ENABLED, snapshot.enabled());
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_POLICY_VERSION, snapshot.policyVersion());
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_RULES_LOADED, snapshot.rulesLoaded());
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_CIRCUIT_BREAKERS_OPEN,
                snapshot.circuitBreakersOpen());
        values.put(GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_METRICS,
                JmxOpenTypeSupport.stringLongMap(
                        GeneratedJmxOpenTypes.SCRUBBING_METRICS,
                        GeneratedJmxWireContract.FIELD_SCRUBBING_METRICS_KEY,
                        GeneratedJmxWireContract.FIELD_SCRUBBING_METRICS_VALUE,
                        snapshot.metrics()));
        published.set(JmxOpenTypeSupport.compositeData(GeneratedJmxOpenTypes.SCRUBBING_OBSERVATION, values));
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
                    snapshotMetrics(runtime));
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
        snapshot.put("runtime_state", attributesVerified
                ? RUNTIME_STATE_ACTIVE
                : RUNTIME_STATE_DEGRADED_FAIL_CLOSED);
        snapshot.put("collapse_mode_activations", runtime.collapseModeActivations());
        snapshot.put("collapsed_spans", runtime.collapsedSpans());
        return snapshot;
    }

}
