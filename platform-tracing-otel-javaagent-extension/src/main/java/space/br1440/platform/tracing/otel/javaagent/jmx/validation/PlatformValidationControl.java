package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

public final class PlatformValidationControl implements PlatformValidationControlMBean {

    private static final JmxOpenTypeSupport.CompositeSchema OBSERVATION_TYPE = buildObservationType();

    private final OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();

    public PlatformValidationControl(OwnerRegistrationSlot<ValidatingSpanProcessor> validatingSlot, UUID agentInstanceId) {
        this.validatingSlot = Objects.requireNonNull(validatingSlot, "validatingSlot");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
    }

    @Override
    public CompositeData getValidationObservation() {
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

        long completedAt = Instant.now().toEpochMilli();
        return JmxOpenTypeSupport.compositeData(
                OBSERVATION_TYPE,
                JmxObservationWireContract.SCHEMA_VERSION,
                sequence,
                agentInstanceId.toString(),
                startedAt,
                completedAt,
                snapshot.ownerPresent(),
                state.generation(),
                offsetNanos,
                snapshot.enabled(),
                snapshot.strict(),
                snapshot.strictRuntimeAllowed(),
                snapshot.configVersion(),
                snapshot.configSource()
        );
    }

    private static JmxOpenTypeSupport.CompositeSchema buildObservationType() {
        LinkedHashMap<String, OpenType<?>> items = JmxOpenTypeSupport.observationFrameItems();
        items.put(JmxObservationWireContract.ITEM_OWNER_PRESENT, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.ITEM_OWNER_GENERATION, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_OWNER_CAPTURED_OFFSET_NANOS, SimpleType.LONG);
        items.put(JmxObservationWireContract.VALIDATION_ITEM_ENABLED, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.VALIDATION_ITEM_STRICT, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.VALIDATION_ITEM_STRICT_RUNTIME_ALLOWED, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.VALIDATION_ITEM_CONFIG_VERSION, SimpleType.LONG);
        items.put(JmxObservationWireContract.VALIDATION_ITEM_CONFIG_LAST_UPDATED_SOURCE, SimpleType.STRING);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.VALIDATION_COMPOSITE_TYPE_NAME, items);
    }

    private record ValidationSnapshot(boolean ownerPresent,
                                      boolean enabled,
                                      boolean strict,
                                      boolean strictRuntimeAllowed,
                                      long configVersion,
                                      String configSource) {

        private static final ValidationSnapshot ABSENT =
                new ValidationSnapshot(false, false, false, false, 0L, "");

        static ValidationSnapshot absent() {
            return ABSENT;
        }

        static ValidationSnapshot from(ValidatingSpanProcessor validating) {
            return new ValidationSnapshot(
                    true,
                    validating.isEnabled(),
                    validating.isStrict(),
                    validating.isStrictRuntimeAllowed(),
                    validating.getPolicyVersion(),
                    validating.getPolicySource()
            );
        }
    }
}
