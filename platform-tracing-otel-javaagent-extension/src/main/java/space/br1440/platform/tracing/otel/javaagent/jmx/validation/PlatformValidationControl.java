package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
        long baseNanos = System.nanoTime();

        OwnerRegistrationState<ValidatingSpanProcessor> state = validatingSlot.capture();
        long offsetNanos = System.nanoTime() - baseNanos;
        boolean ownerPresent = false;
        boolean enabled = false;
        boolean strict = false;
        boolean strictRuntimeAllowed = false;
        long configVersion = 0L;
        String configSource = "";
        if (state instanceof OwnerRegistrationState.Present<ValidatingSpanProcessor> present) {
            ValidatingSpanProcessor validating = present.owner();
            ownerPresent = true;
            enabled = validating.isEnabled();
            strict = validating.isStrict();
            strictRuntimeAllowed = validating.isStrictRuntimeAllowed();
            configVersion = validating.getPolicyVersion();
            configSource = validating.getPolicySource();
        }

        long completedAt = Instant.now().toEpochMilli();
        return JmxOpenTypeSupport.compositeData(OBSERVATION_TYPE,
                JmxObservationWireContract.SCHEMA_VERSION,
                sequence,
                agentInstanceId.toString(),
                startedAt,
                completedAt,
                ownerPresent,
                state.generation(),
                offsetNanos,
                enabled,
                strict,
                strictRuntimeAllowed,
                configVersion,
                configSource);
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
}
