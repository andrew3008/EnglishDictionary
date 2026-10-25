package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxAttribute;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxObservationCodec;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class SamplingJmxView {

    private static final JmxAttribute<SamplingObservation> OBSERVATION = new JmxAttribute<>(
            JmxWireContract.ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION,
            JmxContractPoint.SAMPLING_OBSERVATION,
            JmxOpenTypes.SAMPLING_OBSERVATION,
            SamplingJmxView::decode,
            SamplingObservation::agentInstanceId
    );

    private final JmxReadExecutor executor;
    private final ObjectName objectName;

    SamplingJmxView(JmxReadExecutor executor, ObjectName objectName) {
        this.executor = executor;
        this.objectName = objectName;
    }

    public JmxMBeanRegistrationState registration() {
        return executor.registration(objectName, JmxContractPoint.SAMPLING_REGISTRATION);
    }

    public Optional<SamplingObservation> observation() {
        return executor.read(objectName, OBSERVATION);
    }

    public Optional<Duration> lastSuccessAge() {
        return executor.lastSuccessAge(OBSERVATION.point());
    }

    private static SamplingObservation decode(Object raw) {
        CompositeData composite = JmxObservationCodec.asComposite(raw);
        JmxObservationCodec.requireCompositeTypeName(composite, JmxWireContract.OPEN_TYPE_SAMPLING_OBSERVATION_TYPE_NAME);
        String agentInstanceId = JmxObservationCodec.getAgentInstanceId(
                composite,
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID
        );

        boolean samplerEnabled = JmxObservationCodec.getBoolean(composite, JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED);
        double samplingRatio = JmxObservationCodec.getDouble(composite, JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO);
        long routeRatioCount = JmxObservationCodec.getLong(
                composite,
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_ROUTE_RATIO_COUNT
        );
        String configLastUpdatedSource = JmxObservationCodec.getString(composite, JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_CONFIG_LAST_UPDATED_SOURCE);

        JmxOwnerState<Map<String, Long>> samplerOwnerState = JmxObservationCodec.ownerState(
                composite,
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_PRESENT,
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_GENERATION,
                () -> JmxObservationCodec.getStringLongMap(
                        composite,
                        JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_DECISION_COUNTS,
                        JmxWireContract.FIELD_SAMPLER_DECISION_COUNTS_KEY,
                        JmxWireContract.FIELD_SAMPLER_DECISION_COUNTS_VALUE
                )
        );

        return new SamplingObservation(
                agentInstanceId,
                samplerEnabled,
                samplingRatio,
                routeRatioCount,
                configLastUpdatedSource,
                samplerOwnerState
        );
    }
}
