package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxAttribute;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxObservationCodec;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.time.Duration;
import java.util.Optional;

public final class DiagnosticsJmxView {

    private static final JmxAttribute<DiagnosticsObservation> OBSERVATION = new JmxAttribute<>(
            "DiagnosticsObservation", JmxContractPoint.DIAGNOSTICS_OBSERVATION, DiagnosticsJmxView::decode
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
        JmxObservationCodec.requireCompositeTypeName(composite, JmxObservationWireContract.DIAGNOSTICS_COMPOSITE_TYPE_NAME);
        JmxObservationCodec.requireSchemaVersion(composite);
        String agentInstanceId = JmxObservationCodec.getString(composite, JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID);

        return new DiagnosticsObservation(
                agentInstanceId,
                JmxObservationCodec.getLong(composite, JmxObservationWireContract.DIAGNOSTICS_ITEM_INVALID_CONFIG_COUNT),
                JmxObservationCodec.getStringLongMap(composite, JmxObservationWireContract.DIAGNOSTICS_ITEM_SAFE_WRAPPER_METRICS),
                JmxObservationCodec.getString(composite, JmxObservationWireContract.DIAGNOSTICS_ITEM_PLATFORM_LOG_LEVEL),
                JmxObservationCodec.getBoolean(composite, JmxObservationWireContract.DIAGNOSTICS_ITEM_PROPAGATION_ENABLED));
    }
}
