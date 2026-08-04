package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class PlatformSamplingControl implements PlatformSamplingControlMBean {

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
        long baseNanos = System.nanoTime();

        boolean samplerEnabled = acceptedPolicy.current().samplingEnabled();
        double samplingRatio = acceptedPolicy.current().samplingRatio();
        Map<String, Double> routeRatios = acceptedPolicy.current().samplingRouteRatios();

        OwnerRegistrationState<CompositeSampler> samplerState = compositeSamplerSlot.capture();
        long offsetNanos = System.nanoTime() - baseNanos;
        boolean samplerOwnerPresent = false;
        Map<String, Long> decisionCounts = Map.of();
        if (samplerState instanceof OwnerRegistrationState.Present<CompositeSampler> present) {
            samplerOwnerPresent = true;
            decisionCounts = present.owner().getDecisionCounts();
        }

        long completedAt = Instant.now().toEpochMilli();
        return JmxOpenTypeSupport.compositeData(OBSERVATION_TYPE,
                JmxObservationWireContract.SCHEMA_VERSION,
                sequence,
                agentInstanceId.toString(),
                startedAt,
                completedAt,
                samplerEnabled,
                samplingRatio,
                JmxOpenTypeSupport.stringDoubleMap(ROUTE_RATIOS_TYPE, routeRatios),
                JmxOpenTypeSupport.stringLongMap(DECISION_COUNTS_TYPE, decisionCounts),
                "accepted-state",
                samplerOwnerPresent,
                samplerState.generation(),
                offsetNanos
        );
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
