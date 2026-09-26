package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationCaptureEnvelope;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotStore;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

public final class ValidationObservationSource {

    private final OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot;
    private final ObservationCaptureEnvelope envelope;
    private final ObservationSnapshotStore snapshotStore = new ObservationSnapshotStore(JmxOpenTypes.VALIDATION_OBSERVATION);

    public ValidationObservationSource(OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot,
                                       AgentInstanceId agentInstanceId) {
        this.validatingSlot = Objects.requireNonNull(validatingSlot, "validatingSlot");
        this.envelope = new ObservationCaptureEnvelope(agentInstanceId);
        publishObservationSnapshot();
    }

    public CompositeData getValidationObservation() {
        return snapshotStore.current();
    }

    public synchronized void publishObservationSnapshot() {
        ObservationCaptureEnvelope.OpenCapture openCapture = envelope.open();

        OwnerCapture<ValidatingSpanProcessor> capture = OwnerCapture.of(validatingSlot);
        OwnerRegistrationState<ValidatingSpanProcessor> state = capture.state();
        long offsetNanos = capture.offsetNanos();

        ValidationSnapshot snapshot;
        if (state instanceof OwnerRegistrationState.Present<ValidatingSpanProcessor> present) {
            snapshot = ValidationSnapshot.from(present.owner());
        } else {
            snapshot = ValidationSnapshot.absent();
        }

        Map<String, Object> values = new LinkedHashMap<>();
        openCapture.sealInto(values);
        values.put(JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT, snapshot.ownerPresent());
        values.put(JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_GENERATION, state.generation());
        values.put(JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_CAPTURED_OFFSET_NANOS, offsetNanos);
        values.put(JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_LEVEL, snapshot.validationLevel());
        values.put(JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_VIOLATIONS_TOTAL, snapshot.violationsTotal());
        values.put(JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_VERSION, snapshot.configVersion());
        values.put(JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_LAST_UPDATED_SOURCE, snapshot.configSource());
        snapshotStore.publish(values);
    }

    private record ValidationSnapshot(boolean ownerPresent,
                                      String validationLevel,
                                      long violationsTotal,
                                      long configVersion,
                                      String configSource) {

        private static final ValidationSnapshot ABSENT = new ValidationSnapshot(false, "", 0L, 0L, "");

        static ValidationSnapshot absent() {
            return ABSENT;
        }

        static ValidationSnapshot from(ValidatingSpanProcessor validating) {
            return new ValidationSnapshot(
                    true,
                    validating.validationLevel().name(),
                    validating.getValidationViolationsTotal(),
                    validating.getPolicyVersion(),
                    validating.getPolicySource()
            );
        }
    }
}
