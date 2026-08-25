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
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

public final class ValidationJmxView {

    private static final JmxAttribute<ValidationObservation> OBSERVATION = new JmxAttribute<>(
            "ValidationObservation", JmxContractPoint.VALIDATION_OBSERVATION, ValidationJmxView::decode
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
        JmxObservationCodec.requireCompositeTypeName(composite, JmxObservationWireContract.VALIDATION_COMPOSITE_TYPE_NAME);
        JmxObservationCodec.requireSchemaVersion(composite);
        String agentInstanceId = JmxObservationCodec.getString(composite, JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID);

        JmxOwnerState<ValidationValues> values = JmxObservationCodec.ownerState(composite, () -> {
            boolean enabled = JmxObservationCodec.getBoolean(composite, JmxObservationWireContract.VALIDATION_ITEM_ENABLED);
            boolean strict = JmxObservationCodec.getBoolean(composite, JmxObservationWireContract.VALIDATION_ITEM_STRICT);
            boolean strictRuntimeAllowed = JmxObservationCodec.getBoolean(composite, JmxObservationWireContract.VALIDATION_ITEM_STRICT_RUNTIME_ALLOWED);
            long violationsTotal = JmxObservationCodec.getLong(composite, JmxObservationWireContract.VALIDATION_ITEM_VIOLATIONS_TOTAL);
            long configVersion = JmxObservationCodec.getLong(composite, JmxObservationWireContract.VALIDATION_ITEM_CONFIG_VERSION);
            String configSource = JmxObservationCodec.getString(composite, JmxObservationWireContract.VALIDATION_ITEM_CONFIG_LAST_UPDATED_SOURCE);

            return new ValidationValues(enabled, strict, strictRuntimeAllowed, violationsTotal, configVersion, configSource);
        });

        return new ValidationObservation(agentInstanceId, values);
    }
}
