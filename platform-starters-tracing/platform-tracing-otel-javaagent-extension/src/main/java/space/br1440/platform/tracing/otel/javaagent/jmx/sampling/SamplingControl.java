package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

public final class SamplingControl implements SamplingControlMBean, ObservationSnapshotControl {

    private static final String CONFIG_LAST_UPDATED_SOURCE = "accepted-state";

    private final TracingSettingsSource acceptedPolicy;
    private final OwnerRegistrationSlot<CompositeSampler> compositeSamplerSlot;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();
    private final AtomicReference<CompositeData> published = new AtomicReference<>();

    public SamplingControl(TracingSettingsSource acceptedPolicy,
                           OwnerRegistrationSlot<CompositeSampler> compositeSamplerSlot,
                           UUID agentInstanceId) {
        this.acceptedPolicy = Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
        this.compositeSamplerSlot = Objects.requireNonNull(compositeSamplerSlot, "compositeSamplerSlot");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        publishObservationSnapshot();
    }

    @Override
    public CompositeData getSamplingObservation() {
        return published.get();
    }

    @Override
    public synchronized void publishObservationSnapshot() {
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

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SCHEMA_VERSION,
                GeneratedJmxWireContract.OBSERVATION_SCHEMA_VERSION);
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_SEQUENCE, sequence);
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID,
                agentInstanceId.toString());
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_STARTED_AT, startedAt);
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_COMPLETED_AT,
                Instant.now().toEpochMilli());
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED,
                policySnapshot.samplerEnabled());
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO,
                policySnapshot.samplingRatio());
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_ROUTE_RATIOS,
                JmxOpenTypeSupport.stringDoubleMap(
                        GeneratedJmxOpenTypes.SAMPLING_ROUTE_RATIOS,
                        GeneratedJmxWireContract.FIELD_SAMPLING_ROUTE_RATIOS_KEY,
                        GeneratedJmxWireContract.FIELD_SAMPLING_ROUTE_RATIOS_VALUE,
                        policySnapshot.routeRatios()));
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_DECISION_COUNTS,
                JmxOpenTypeSupport.stringLongMap(
                        GeneratedJmxOpenTypes.SAMPLER_DECISION_COUNTS,
                        GeneratedJmxWireContract.FIELD_SAMPLER_DECISION_COUNTS_KEY,
                        GeneratedJmxWireContract.FIELD_SAMPLER_DECISION_COUNTS_VALUE,
                        samplerSnapshot.decisionCounts()));
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_CONFIG_LAST_UPDATED_SOURCE,
                CONFIG_LAST_UPDATED_SOURCE);
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_PRESENT,
                samplerSnapshot.present());
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_GENERATION,
                samplerState.generation());
        values.put(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_CAPTURED_OFFSET_NANOS,
                offsetNanos);
        published.set(JmxOpenTypeSupport.compositeData(GeneratedJmxOpenTypes.SAMPLING_OBSERVATION, values));
    }

    private record AcceptedSamplingPolicySnapshot(boolean samplerEnabled,
                                                  double samplingRatio,
                                                  Map<String, Double> routeRatios) {

        static AcceptedSamplingPolicySnapshot capture(TracingSettingsSource acceptedPolicy) {
            TracingSettingsRevision policy = acceptedPolicy.current();
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

}
