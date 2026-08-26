package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

public final class ValidationControl implements ValidationControlMBean, ObservationSnapshotControl {

    private final OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();
    private final AtomicReference<CompositeData> published = new AtomicReference<>();

    public ValidationControl(OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot, UUID agentInstanceId) {
        this.validatingSlot = Objects.requireNonNull(validatingSlot, "validatingSlot");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        publishObservationSnapshot();
    }

    @Override
    public CompositeData getValidationObservation() {
        return published.get();
    }

    @Override
    public synchronized void publishObservationSnapshot() {
        long sequence = captureSequence.incrementAndGet();
        long startedAt = Instant.now().toEpochMilli();

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
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_SCHEMA_VERSION,
                GeneratedJmxWireContract.OBSERVATION_SCHEMA_VERSION);
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_SEQUENCE, sequence);
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_AGENT_INSTANCE_ID,
                agentInstanceId.toString());
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_STARTED_AT, startedAt);
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_COMPLETED_AT,
                Instant.now().toEpochMilli());
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT, snapshot.ownerPresent());
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_GENERATION, state.generation());
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_CAPTURED_OFFSET_NANOS, offsetNanos);
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_ENABLED, snapshot.enabled());
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_STRICT, snapshot.strict());
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_STRICT_RUNTIME_ALLOWED,
                snapshot.strictRuntimeAllowed());
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_VIOLATIONS_TOTAL,
                snapshot.violationsTotal());
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_VERSION,
                snapshot.configVersion());
        values.put(GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_CONFIG_LAST_UPDATED_SOURCE,
                snapshot.configSource());
        published.set(JmxOpenTypeSupport.compositeData(GeneratedJmxOpenTypes.VALIDATION_OBSERVATION, values));
    }

    private record ValidationSnapshot(boolean ownerPresent,
                                      boolean enabled,
                                      boolean strict,
                                      boolean strictRuntimeAllowed,
                                      long violationsTotal,
                                      long configVersion,
                                      String configSource) {

        private static final ValidationSnapshot ABSENT =
                new ValidationSnapshot(false, false, false, false, 0L, 0L, "");

        static ValidationSnapshot absent() {
            return ABSENT;
        }

        static ValidationSnapshot from(ValidatingSpanProcessor validating) {
            return new ValidationSnapshot(
                    true,
                    validating.isEnabled(),
                    validating.isStrict(),
                    validating.isStrictRuntimeAllowed(),
                    validating.getValidationViolationsTotal(),
                    validating.getPolicyVersion(),
                    validating.getPolicySource()
            );
        }
    }
}
