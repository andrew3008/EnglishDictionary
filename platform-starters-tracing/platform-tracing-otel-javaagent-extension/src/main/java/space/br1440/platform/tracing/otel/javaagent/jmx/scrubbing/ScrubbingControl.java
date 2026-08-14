package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;

import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

public final class ScrubbingControl implements ScrubbingControlMBean {

    private static final long RUNTIME_STATE_ACTIVE = 0L;
    private static final long RUNTIME_STATE_DEGRADED_FAIL_CLOSED = 1L;

    private static final TabularType METRICS_TYPE = JmxOpenTypeSupport.stringLongMapType(
            "space.br1440.platform.tracing.ScrubbingMetrics", "ScrubbingMetricsEntry"
    );
    private static final JmxOpenTypeSupport.CompositeSchema OBSERVATION_TYPE = buildObservationType();

    private final OwnerRegistrationSlot<ScrubbingRuntime> scrubbingSlot;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();

    public ScrubbingControl(OwnerRegistrationSlot<ScrubbingRuntime> scrubbingSlot, UUID agentInstanceId) {
        this.scrubbingSlot = Objects.requireNonNull(scrubbingSlot, "scrubbingSlot");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
    }

    @Override
    public CompositeData getScrubbingObservation() {
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

        long completedAt = Instant.now().toEpochMilli();
        return JmxOpenTypeSupport.compositeData(
                OBSERVATION_TYPE,
                JmxObservationWireContract.SCHEMA_VERSION,
                sequence,
                agentInstanceId.toString(),
                startedAt,
                completedAt,
                snapshot.ownerPresent(),
                state.generation(),
                offsetNanos,
                snapshot.enabled(),
                snapshot.policyVersion(),
                snapshot.rulesLoaded(),
                snapshot.circuitBreakersOpen(),
                JmxOpenTypeSupport.stringLongMap(METRICS_TYPE, snapshot.metrics())
        );
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

    private static JmxOpenTypeSupport.CompositeSchema buildObservationType() {
        LinkedHashMap<String, OpenType<?>> items = JmxOpenTypeSupport.observationFrameItems();
        items.put(JmxObservationWireContract.ITEM_OWNER_PRESENT, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.ITEM_OWNER_GENERATION, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_OWNER_CAPTURED_OFFSET_NANOS, SimpleType.LONG);
        items.put(JmxObservationWireContract.SCRUBBING_ITEM_ENABLED, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.SCRUBBING_ITEM_POLICY_VERSION, SimpleType.LONG);
        items.put(JmxObservationWireContract.SCRUBBING_ITEM_RULES_LOADED, SimpleType.LONG);
        items.put(JmxObservationWireContract.SCRUBBING_ITEM_CIRCUIT_BREAKERS_OPEN, SimpleType.LONG);
        items.put(JmxObservationWireContract.SCRUBBING_ITEM_METRICS, METRICS_TYPE);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.SCRUBBING_COMPOSITE_TYPE_NAME, items);
    }
}
