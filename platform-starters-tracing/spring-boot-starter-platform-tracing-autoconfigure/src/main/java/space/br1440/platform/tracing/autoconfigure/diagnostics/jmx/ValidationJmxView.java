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
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class ValidationJmxView {

    private static final JmxAttribute<ValidationObservation> OBSERVATION = new JmxAttribute<>(
            JmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
            JmxContractPoint.VALIDATION_OBSERVATION,
            JmxOpenTypes.VALIDATION_OBSERVATION,
            ValidationJmxView::decode,
            ValidationObservation::agentInstanceId
    );

    private final JmxReadExecutor executor;
    private final ObjectName objectName;

    ValidationJmxView(JmxReadExecutor executor, ObjectName objectName) {
        this.executor = executor;
        this.objectName = objectName;
    }

    public JmxMBeanRegistrationState registration() {
        return executor.registration(objectName, JmxContractPoint.VALIDATION_REGISTRATION);
    }

    public Optional<ValidationObservation> observation() {
        return executor.read(objectName, OBSERVATION);
    }

    public Optional<Duration> lastSuccessAge() {
        return executor.lastSuccessAge(OBSERVATION.point());
    }

    private static ValidationObservation decode(Object raw) {
        CompositeData composite = JmxObservationCodec.asComposite(raw);
        JmxObservationCodec.requireCompositeTypeName(composite, JmxWireContract.OPEN_TYPE_VALIDATION_OBSERVATION_TYPE_NAME);

        JmxOwnerState<ValidationValues> values = JmxObservationCodec.ownerState(
                composite,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_GENERATION,
                () -> decodeValues(composite)
        );

        String agentInstanceId = JmxObservationCodec.getAgentInstanceId(
                composite,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_AGENT_INSTANCE_ID
        );
        return new ValidationObservation(agentInstanceId, values);
    }

    private static ValidationValues decodeValues(CompositeData composite) {
        String validationLevel = JmxObservationCodec.getString(
                composite, JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_LEVEL
        );

        long violationsTotal = JmxObservationCodec.getLong(
                composite, JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_VIOLATIONS_TOTAL
        );

        long configVersion = JmxObservationCodec.getLong(
                composite, JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_VERSION
        );

        String configSource = JmxObservationCodec.getString(
                composite, JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_LAST_UPDATED_SOURCE
        );

        return new ValidationValues(
                validationLevel, violationsTotal, configVersion, configSource
        );
    }
}
