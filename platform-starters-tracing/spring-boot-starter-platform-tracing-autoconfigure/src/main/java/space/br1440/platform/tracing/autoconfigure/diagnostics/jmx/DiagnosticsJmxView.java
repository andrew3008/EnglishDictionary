package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import java.time.Duration;
import java.util.Optional;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxAttribute;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxObservationCodec;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class DiagnosticsJmxView {

    private static final JmxAttribute<DiagnosticsObservation> OBSERVATION = new JmxAttribute<>(
            JmxWireContract.ATTRIBUTE_DIAGNOSTICS_OBSERVATION_DIAGNOSTICS_OBSERVATION,
            JmxContractPoint.DIAGNOSTICS_OBSERVATION,
            JmxOpenTypes.DIAGNOSTICS_OBSERVATION,
            DiagnosticsJmxView::decode,
            DiagnosticsObservation::agentInstanceId
    );

    private final JmxReadExecutor executor;
    private final ObjectName objectName;

    DiagnosticsJmxView(JmxReadExecutor executor, ObjectName objectName) {
        this.executor = executor;
        this.objectName = objectName;
    }

    public JmxMBeanRegistrationState registration() {
        return executor.registration(objectName, JmxContractPoint.DIAGNOSTICS_REGISTRATION);
    }

    public Optional<DiagnosticsObservation> observation() {
        return executor.read(objectName, OBSERVATION);
    }

    public Optional<Duration> lastSuccessAge() {
        return executor.lastSuccessAge(OBSERVATION.point());
    }

    private static DiagnosticsObservation decode(Object raw) {
        CompositeData composite = JmxObservationCodec.asComposite(raw);
        JmxObservationCodec.requireCompositeTypeName(composite, JmxWireContract.OPEN_TYPE_DIAGNOSTICS_OBSERVATION_TYPE_NAME);
        String agentInstanceId = JmxObservationCodec.getAgentInstanceId(
                composite,
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID
        );

        return new DiagnosticsObservation(
                agentInstanceId,
                JmxObservationCodec.getLong(composite, JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_INVALID_CONFIG_COUNT),
                JmxObservationCodec.getStringLongMap(
                        composite,
                        JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_SAFE_WRAPPER_METRICS,
                        JmxWireContract.FIELD_SAFE_WRAPPER_METRICS_KEY,
                        JmxWireContract.FIELD_SAFE_WRAPPER_METRICS_VALUE
                )
        );
    }
}
