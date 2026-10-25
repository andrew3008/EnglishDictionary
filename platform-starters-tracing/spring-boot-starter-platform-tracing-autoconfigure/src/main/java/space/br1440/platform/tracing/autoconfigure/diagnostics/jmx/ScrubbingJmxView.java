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
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class ScrubbingJmxView {

    private static final JmxAttribute<ScrubbingObservation> OBSERVATION = new JmxAttribute<>(
            JmxWireContract.ATTRIBUTE_SCRUBBING_OBSERVATION_SCRUBBING_OBSERVATION,
            JmxContractPoint.SCRUBBING_OBSERVATION,
            JmxOpenTypes.SCRUBBING_OBSERVATION,
            ScrubbingJmxView::decode,
            ScrubbingObservation::agentInstanceId
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
        JmxObservationCodec.requireCompositeTypeName(composite, JmxWireContract.OPEN_TYPE_SCRUBBING_OBSERVATION_TYPE_NAME);
        String agentInstanceId = JmxObservationCodec.getAgentInstanceId(
                composite,
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_AGENT_INSTANCE_ID
        );

        JmxOwnerState<ScrubbingValues> values = JmxObservationCodec.ownerState(
                composite,
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_PRESENT,
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_GENERATION,
                () -> {
                    boolean enabled = JmxObservationCodec.getBoolean(
                            composite,
                            JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_ENABLED
                    );

                    long policyVersion = JmxObservationCodec.getLong(
                            composite,
                            JmxWireContract.FIELD_SCRUBBING_OBSERVATION_POLICY_VERSION
                    );

                    long rulesLoaded = JmxObservationCodec.getLong(
                            composite,
                            JmxWireContract.FIELD_SCRUBBING_OBSERVATION_RULES_LOADED
                    );

                    Map<String, Long> metrics = JmxObservationCodec.getStringLongMap(
                            composite,
                            JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_METRICS,
                            JmxWireContract.FIELD_SCRUBBING_METRICS_KEY,
                            JmxWireContract.FIELD_SCRUBBING_METRICS_VALUE
                    );

                    return new ScrubbingValues(enabled, policyVersion, rulesLoaded, metrics);
                });

        return new ScrubbingObservation(agentInstanceId, values);
    }
}
