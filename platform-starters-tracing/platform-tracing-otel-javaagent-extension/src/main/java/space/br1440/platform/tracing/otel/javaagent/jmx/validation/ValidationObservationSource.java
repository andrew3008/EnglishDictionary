package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationCaptureEnvelope;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotStore;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

public final class ValidationObservationSource {

    private final OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot;
    private final TracingSettingsSource acceptedPolicy;
    private final ObservationCaptureEnvelope envelope;
    private final ObservationSnapshotStore snapshotStore = new ObservationSnapshotStore(JmxOpenTypes.VALIDATION_OBSERVATION);

    public ValidationObservationSource(OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot,
                                       TracingSettingsSource acceptedPolicy,
                                       AgentInstanceId agentInstanceId) {
        this.validatingSlot = Objects.requireNonNull(validatingSlot, "validatingSlot");
        this.acceptedPolicy = Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
        this.envelope = new ObservationCaptureEnvelope(agentInstanceId);
        publishObservationSnapshot();
    }

    public CompositeData getValidationObservation() {
        return snapshotStore.current();
    }

    public synchronized void publishObservationSnapshot() {
        OwnerRegistrationState<ValidatingSpanProcessor> state = validatingSlot.capture();
        TracingSettingsRevision policy = acceptedPolicy.current();

        ValidationSnapshot snapshot = ValidationSnapshot.capture(state, policy);

        Map<String, Object> values = new LinkedHashMap<>();
        envelope.putInto(values);
        values.put(JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT, snapshot.ownerPresent());
        values.put(JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_GENERATION, state.generation());
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

        static ValidationSnapshot capture(OwnerRegistrationState<ValidatingSpanProcessor> state,
                                          TracingSettingsRevision policy) {
            //noinspection resource — процессор принадлежит OwnerRegistrationSlot и здесь не закрывается
            long violationsTotal =
                    state instanceof OwnerRegistrationState.Present<ValidatingSpanProcessor> present
                            ? present.owner().getValidationViolationsTotal()
                            : 0L;
            return new ValidationSnapshot(
                    state instanceof OwnerRegistrationState.Present<ValidatingSpanProcessor>,
                    policy.validationLevel().name(),
                    violationsTotal,
                    policy.acceptedSequence(),
                    policy.sourceAuthority().name()
            );
        }
    }
}
