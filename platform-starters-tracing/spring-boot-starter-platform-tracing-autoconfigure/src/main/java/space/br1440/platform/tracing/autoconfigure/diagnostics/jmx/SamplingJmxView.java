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
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

public final class SamplingJmxView {

    private static final JmxAttribute<SamplingObservation> OBSERVATION = new JmxAttribute<>(
            "SamplingObservation", JmxContractPoint.SAMPLING_OBSERVATION, SamplingJmxView::decode
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
        JmxObservationCodec.requireCompositeTypeName(composite, JmxObservationWireContract.SAMPLING_COMPOSITE_TYPE_NAME);
        JmxObservationCodec.requireSchemaVersion(composite);
        String agentInstanceId = JmxObservationCodec.getString(composite, JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID);

        boolean samplerEnabled = JmxObservationCodec.getBoolean(composite, JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_ENABLED);
        double samplingRatio = JmxObservationCodec.getDouble(composite, JmxObservationWireContract.SAMPLING_ITEM_SAMPLING_RATIO);
        int liveRouteRatioCount = JmxObservationCodec.getTabularSize(composite, JmxObservationWireContract.SAMPLING_ITEM_ROUTE_RATIOS);
        String configLastUpdatedSource = JmxObservationCodec.getString(composite, JmxObservationWireContract.SAMPLING_ITEM_CONFIG_LAST_UPDATED_SOURCE);

        JmxOwnerState<Map<String, Long>> samplerOwnerState = JmxObservationCodec.ownerState(
                composite,
                JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_PRESENT,
                JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_GENERATION,
                () -> JmxObservationCodec.getStringLongMap(composite, JmxObservationWireContract.SAMPLING_ITEM_DECISION_COUNTS)
        );

        return new SamplingObservation(agentInstanceId, samplerEnabled, samplingRatio, liveRouteRatioCount, configLastUpdatedSource, samplerOwnerState);
    }
}
