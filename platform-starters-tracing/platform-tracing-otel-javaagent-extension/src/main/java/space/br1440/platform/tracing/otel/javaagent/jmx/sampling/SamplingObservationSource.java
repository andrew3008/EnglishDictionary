package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationCaptureEnvelope;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotStore;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

public final class SamplingObservationSource {

    private final TracingSettingsSource acceptedPolicy;
    private final OwnerRegistrationSlot<CompositeSampler> compositeSamplerSlot;
    private final ObservationCaptureEnvelope envelope;
    private final ObservationSnapshotStore snapshotStore = new ObservationSnapshotStore(JmxOpenTypes.SAMPLING_OBSERVATION);

    public SamplingObservationSource(TracingSettingsSource acceptedPolicy,
                                     OwnerRegistrationSlot<CompositeSampler> compositeSamplerSlot,
                                     AgentInstanceId agentInstanceId) {
        this.acceptedPolicy = Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
        this.compositeSamplerSlot = Objects.requireNonNull(compositeSamplerSlot, "compositeSamplerSlot");
        this.envelope = new ObservationCaptureEnvelope(agentInstanceId);
        publishObservationSnapshot();
    }

    public CompositeData getSamplingObservation() {
        return snapshotStore.current();
    }

    public synchronized void publishObservationSnapshot() {
        AcceptedSamplingPolicySnapshot policySnapshot = AcceptedSamplingPolicySnapshot.capture(acceptedPolicy);

        OwnerRegistrationState<CompositeSampler> samplerState = compositeSamplerSlot.capture();

        SamplerOwnerSnapshot samplerSnapshot;
        if (samplerState instanceof OwnerRegistrationState.Present<CompositeSampler> present) {
            samplerSnapshot = SamplerOwnerSnapshot.from(present.owner());
        } else {
            samplerSnapshot = SamplerOwnerSnapshot.absent();
        }

        Map<String, Object> values = new LinkedHashMap<>();
        envelope.putInto(values);
        values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED, policySnapshot.samplerEnabled());
        values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO, policySnapshot.samplingRatio());
        values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_ROUTE_RATIO_COUNT, policySnapshot.routeRatioCount());
        values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_DECISION_COUNTS,
                JmxOpenTypeSupport.stringLongMap(
                        JmxOpenTypes.SAMPLER_DECISION_COUNTS,
                        JmxWireContract.FIELD_SAMPLER_DECISION_COUNTS_KEY,
                        JmxWireContract.FIELD_SAMPLER_DECISION_COUNTS_VALUE,
                        samplerSnapshot.decisionCounts()
                )
        );
        values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_CONFIG_LAST_UPDATED_SOURCE, policySnapshot.configSource());
        values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_PRESENT, samplerSnapshot.present());
        values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_GENERATION, samplerState.generation());
        snapshotStore.publish(values);
    }

    private record AcceptedSamplingPolicySnapshot(boolean samplerEnabled,
                                                  double samplingRatio,
                                                  long routeRatioCount,
                                                  String configSource) {

        static AcceptedSamplingPolicySnapshot capture(TracingSettingsSource acceptedPolicy) {
            TracingSettingsRevision policy = acceptedPolicy.current();
            return new AcceptedSamplingPolicySnapshot(
                    policy.samplingEnabled(),
                    policy.samplingRatio(),
                    policy.samplingRouteRatios().size(),
                    policy.sourceAuthority().name()
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
