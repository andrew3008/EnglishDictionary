package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.CausePolicy;

final class AgentLogFieldsBuilder implements AgentLogFields {

    private final AgentLogEventType eventType;
    private final EnumMap<AgentLogField, String> values = new EnumMap<>(AgentLogField.class);

    private Throwable cause;
    private boolean causeAssigned;

    AgentLogFieldsBuilder(AgentLogEventType eventType) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
    }

    @Override
    public AgentLogFields putString(AgentLogField field, String value) {
        AgentLogField requiredField = requireField(field, AgentLogField.Kind.STRING);
        String requiredValue = Objects.requireNonNull(value, "value");
        if (!requiredField.allowsBlank() && StringUtils.isNullOrBlank(requiredValue)) {
            throw new IllegalArgumentException("String field value must not be blank");
        }

        put(requiredField, AgentLogTextSanitizer.normalizeBounded(requiredValue, AgentLogRecord.MAX_ARGUMENT_CHARS));
        return this;
    }

    @Override
    public AgentLogFields putLong(AgentLogField field, long value) {
        AgentLogField requiredField = requireField(field, AgentLogField.Kind.LONG);
        put(requiredField, Long.toString(value));
        return this;
    }

    @Override
    public AgentLogFields putEnum(AgentLogField field, Enum<?> value) {
        AgentLogField requiredField = requireField(field, AgentLogField.Kind.ENUM);
        Enum<?> requiredValue = Objects.requireNonNull(value, "value");
        put(requiredField, requiredValue.name());
        return this;
    }

    @Override
    public AgentLogFields cause(Throwable value) {
        Throwable requiredCause = Objects.requireNonNull(value, "cause");
        if (eventType.definition().causePolicy() == CausePolicy.FORBIDDEN) {
            throw new IllegalArgumentException("Cause is forbidden for event " + eventType.name());
        }

        if (causeAssigned) {
            throw new IllegalArgumentException("Cause must not be assigned more than once");
        }

        cause = requiredCause;
        causeAssigned = true;
        return this;
    }

    Snapshot snapshot() {
        if (eventType.definition().causePolicy() == CausePolicy.REQUIRED && !causeAssigned) {
            throw new IllegalArgumentException("Cause is required for event " + eventType.name());
        }

        Map<String, String> fields = new LinkedHashMap<>(values.size());
        values.forEach((field, value) -> fields.put(field.wireName(), value));
        return new Snapshot(
                Collections.unmodifiableMap(fields),
                causeAssigned ? cause.getClass().getName() : null,
                causeAssigned ? BoundedThrowableRenderer.render(cause) : null
        );
    }

    private AgentLogField requireField(AgentLogField field, AgentLogField.Kind expectedKind) {
        AgentLogField requiredField = Objects.requireNonNull(field, "field");
        if (!eventType.allowedFields().contains(requiredField)) {
            throw new IllegalArgumentException(
                    "Field " + requiredField.name() + " is not allowed for event " + eventType.name()
            );
        }

        if (requiredField.kind() != expectedKind) {
            throw new IllegalArgumentException(
                    "Field " + requiredField.name() + " requires " + requiredField.kind() + " values"
            );
        }

        return requiredField;
    }

    private void put(AgentLogField field, String value) {
        if (values.putIfAbsent(field, value) != null) {
            throw new IllegalArgumentException("Field " + field.name() + " must not be assigned more than once");
        }
    }

    record Snapshot(Map<String, String> fields, String errorType, String throwableDigest) {
    }
}
