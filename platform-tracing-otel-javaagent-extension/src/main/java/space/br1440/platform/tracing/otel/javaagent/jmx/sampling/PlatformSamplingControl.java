package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

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

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

public final class PlatformSamplingControl implements PlatformSamplingControlMBean {

    private static final String CONFIG_LAST_UPDATED_SOURCE = "accepted-state";

    private static final TabularType ROUTE_RATIOS_TYPE = JmxOpenTypeSupport.stringDoubleMapType(
            "space.br1440.platform.tracing.SamplingRouteRatios", "SamplingRouteRatioEntry"
    );

    private static final TabularType DECISION_COUNTS_TYPE = JmxOpenTypeSupport.stringLongMapType(
            "space.br1440.platform.tracing.SamplerDecisionCounts", "SamplerDecisionCountEntry"
    );

    private static final JmxOpenTypeSupport.CompositeSchema OBSERVATION_TYPE = buildObservationType();

    private final AcceptedPolicyAccess acceptedPolicy;
    private final OwnerRegistrationSlot<CompositeSampler> compositeSamplerSlot;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();

    public PlatformSamplingControl(AcceptedPolicyAccess acceptedPolicy,
                                   OwnerRegistrationSlot<CompositeSampler> compositeSamplerSlot,
                                   UUID agentInstanceId) {
        this.acceptedPolicy = Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
        this.compositeSamplerSlot = Objects.requireNonNull(compositeSamplerSlot, "compositeSamplerSlot");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
    }

    @Override
    public CompositeData getSamplingObservation() {
        long sequence = captureSequence.incrementAndGet();
        long startedAt = Instant.now().toEpochMilli();

        AcceptedSamplingPolicySnapshot policySnapshot = AcceptedSamplingPolicySnapshot.capture(acceptedPolicy);

        OwnerCapture<CompositeSampler> capture = OwnerCapture.of(compositeSamplerSlot);
        OwnerRegistrationState<CompositeSampler> samplerState = capture.state();
        long offsetNanos = capture.offsetNanos();

        SamplerOwnerSnapshot samplerSnapshot;
        if (samplerState instanceof OwnerRegistrationState.Present<CompositeSampler> present) {
            samplerSnapshot = SamplerOwnerSnapshot.from(present.owner());
        } else {
            samplerSnapshot = SamplerOwnerSnapshot.absent();
        }

        long completedAt = Instant.now().toEpochMilli();
        return JmxOpenTypeSupport.compositeData(
                OBSERVATION_TYPE,
                JmxObservationWireContract.SCHEMA_VERSION,
                sequence,
                agentInstanceId.toString(),
                startedAt,
                completedAt,
                policySnapshot.samplerEnabled(),
                policySnapshot.samplingRatio(),
                JmxOpenTypeSupport.stringDoubleMap(ROUTE_RATIOS_TYPE, policySnapshot.routeRatios()),
                JmxOpenTypeSupport.stringLongMap(DECISION_COUNTS_TYPE, samplerSnapshot.decisionCounts()),
                CONFIG_LAST_UPDATED_SOURCE,
                samplerSnapshot.present(),
                samplerState.generation(),
                offsetNanos
        );
    }

    private record AcceptedSamplingPolicySnapshot(boolean samplerEnabled,
                                                  double samplingRatio,
                                                  Map<String, Double> routeRatios) {

        static AcceptedSamplingPolicySnapshot capture(AcceptedPolicyAccess acceptedPolicy) {
            var policy = acceptedPolicy.current();
            return new AcceptedSamplingPolicySnapshot(
                    policy.samplingEnabled(),
                    policy.samplingRatio(),
                    policy.samplingRouteRatios()
            );
        }
    }

    private record SamplerOwnerSnapshot(boolean present, Map<String, Long> decisionCounts) {

        private static final SamplerOwnerSnapshot ABSENT = new SamplerOwnerSnapshot(false, Map.of());

        static SamplerOwnerSnapshot absent() {
            return ABSENT;
        }

        static SamplerOwnerSnapshot from(CompositeSampler sampler) {
            return new SamplerOwnerSnapshot(true, sampler.getDecisionCounts());
        }
    }

    private static JmxOpenTypeSupport.CompositeSchema buildObservationType() {
        LinkedHashMap<String, OpenType<?>> items = JmxOpenTypeSupport.observationFrameItems();
        items.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_ENABLED, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLING_RATIO, SimpleType.DOUBLE);
        items.put(JmxObservationWireContract.SAMPLING_ITEM_ROUTE_RATIOS, ROUTE_RATIOS_TYPE);
        items.put(JmxObservationWireContract.SAMPLING_ITEM_DECISION_COUNTS, DECISION_COUNTS_TYPE);
        items.put(JmxObservationWireContract.SAMPLING_ITEM_CONFIG_LAST_UPDATED_SOURCE, SimpleType.STRING);
        items.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_PRESENT, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_GENERATION, SimpleType.LONG);
        items.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_CAPTURED_OFFSET_NANOS, SimpleType.LONG);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.SAMPLING_COMPOSITE_TYPE_NAME, items);
    }
}
