package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxAttribute;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxObservationCodec;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

public final class ScrubbingJmxView {

    private static final JmxAttribute<ScrubbingObservation> OBSERVATION = new JmxAttribute<>(
            "ScrubbingObservation", JmxContractPoint.SCRUBBING_OBSERVATION, ScrubbingJmxView::decode
    );

    private final JmxReadExecutor executor;
    private final ObjectName objectName;

    ScrubbingJmxView(JmxReadExecutor executor, ObjectName objectName) {
        this.executor = executor;
        this.objectName = objectName;
    }

    public JmxMBeanRegistrationState registration() {
        return executor.registration(objectName, JmxContractPoint.SCRUBBING_REGISTRATION);
    }

    public Optional<ScrubbingObservation> observation() {
        return executor.read(objectName, OBSERVATION);
    }

    public Optional<Duration> lastSuccessAge() {
        return executor.lastSuccessAge(OBSERVATION.point());
    }

    private static ScrubbingObservation decode(Object raw) {
        CompositeData composite = JmxObservationCodec.asComposite(raw);
        JmxObservationCodec.requireCompositeTypeName(composite, JmxObservationWireContract.SCRUBBING_COMPOSITE_TYPE_NAME);
        JmxObservationCodec.requireSchemaVersion(composite);
        String agentInstanceId = JmxObservationCodec.getString(composite, JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID);

        JmxOwnerState<ScrubbingValues> values = JmxObservationCodec.ownerState(composite, () -> {
            boolean enabled = JmxObservationCodec.getBoolean(composite, JmxObservationWireContract.SCRUBBING_ITEM_ENABLED);
            long policyVersion = JmxObservationCodec.getLong(composite, JmxObservationWireContract.SCRUBBING_ITEM_POLICY_VERSION);
            long rulesLoaded = JmxObservationCodec.getLong(composite, JmxObservationWireContract.SCRUBBING_ITEM_RULES_LOADED);
            long circuitBreakersOpen = JmxObservationCodec.getLong(composite, JmxObservationWireContract.SCRUBBING_ITEM_CIRCUIT_BREAKERS_OPEN);
            Map<String, Long> metrics = JmxObservationCodec.getStringLongMap(composite, JmxObservationWireContract.SCRUBBING_ITEM_METRICS);

            return new ScrubbingValues(enabled, policyVersion, rulesLoaded, circuitBreakersOpen, metrics);
        });

        return new ScrubbingObservation(agentInstanceId, values);
    }
}
