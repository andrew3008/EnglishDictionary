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
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;

public final class PlatformScrubbingControl implements PlatformScrubbingControlMBean {

    private static final TabularType METRICS_TYPE = JmxOpenTypeSupport.stringLongMapType(
            "space.br1440.platform.tracing.ScrubbingMetrics", "ScrubbingMetricsEntry"
    );
    private static final JmxOpenTypeSupport.CompositeSchema OBSERVATION_TYPE = buildObservationType();

    private final OwnerRegistrationSlot<ScrubbingSpanProcessor> scrubbingSlot;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();

    public PlatformScrubbingControl(OwnerRegistrationSlot<ScrubbingSpanProcessor> scrubbingSlot, UUID agentInstanceId) {
        this.scrubbingSlot = Objects.requireNonNull(scrubbingSlot, "scrubbingSlot");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
    }

    @Override
    public CompositeData getScrubbingObservation() {
        long sequence = captureSequence.incrementAndGet();
        long startedAt = Instant.now().toEpochMilli();

        OwnerCapture<ScrubbingSpanProcessor> capture = OwnerCapture.of(scrubbingSlot);
        OwnerRegistrationState<ScrubbingSpanProcessor> state = capture.state();
        long offsetNanos = capture.offsetNanos();

        ScrubbingSnapshot snapshot;
        if (state instanceof OwnerRegistrationState.Present<ScrubbingSpanProcessor> present) {
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

        static ScrubbingSnapshot from(ScrubbingSpanProcessor scrubbing) {
            return new ScrubbingSnapshot(
                    true,
                    scrubbing.isEnabled(),
                    scrubbing.getPolicyVersion(),
                    scrubbing.getRuleCount(),
                    scrubbing.getOpenBreakerCount(),
                    snapshotMetrics(scrubbing));
        }
    }

    private static Map<String, Long> snapshotMetrics(ScrubbingSpanProcessor scrubbing) {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("drop", scrubbing.getDroppedActions());
        snapshot.put("hash", scrubbing.getHashedActions());
        snapshot.put("mask", scrubbing.getMaskedActions());
        snapshot.put("truncate", scrubbing.getTruncatedActions());
        snapshot.put("failures", scrubbing.getFailures());
        snapshot.put("hash_missing_key", scrubbing.getHashMissingKey());
        snapshot.put("rules.loaded", scrubbing.getRuleCount());
        snapshot.put("circuit_breakers.open", scrubbing.getOpenBreakerCount());
        snapshot.put("circuit_breakers.failures.total", scrubbing.getTotalBreakerFailures());
        snapshot.put("enabled", scrubbing.isEnabled() ? 1L : 0L);
        snapshot.put("policy.version", scrubbing.getPolicyVersion());
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
