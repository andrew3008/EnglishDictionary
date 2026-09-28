package space.br1440.platform.tracing.otel.javaagent.logging.event;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.CausePolicy;

public final class AgentLogEventDefinition {

    static final int MAX_FIELDS = 16;

    private final AgentEventCode eventCode;
    private final AgentLogLevel level;
    private final String message;
    private final CausePolicy causePolicy;
    private final Set<AgentLogField> allowedFields;

    private AgentLogEventDefinition(AgentEventCode eventCode,
                                    AgentLogLevel level,
                                    String message,
                                    CausePolicy causePolicy,
                                    AgentLogField[] allowedFields) {
        this.eventCode = Objects.requireNonNull(eventCode, "eventCode must not be null");
        this.level = Objects.requireNonNull(level, "level must not be null").requireEventLevel();
        this.message = requireNonBlank(message);
        this.causePolicy = Objects.requireNonNull(causePolicy, "causePolicy must not be null");
        this.allowedFields = copyAllowedFields(allowedFields);
    }

    static AgentLogEventDefinition noCause(AgentEventCode eventCode,
                                           AgentLogLevel level,
                                           String message,
                                           AgentLogField... allowedFields) {
        return new AgentLogEventDefinition(eventCode, level, message, CausePolicy.FORBIDDEN, allowedFields);
    }

    static AgentLogEventDefinition requiresCause(AgentEventCode eventCode,
                                                 AgentLogLevel level,
                                                 String message,
                                                 AgentLogField... allowedFields) {
        return new AgentLogEventDefinition(eventCode, level, message, CausePolicy.REQUIRED, allowedFields);
    }

    static AgentLogEventDefinition optionalCause(AgentEventCode eventCode,
                                                 AgentLogLevel level,
                                                 String message,
                                                 AgentLogField... allowedFields) {
        return new AgentLogEventDefinition(eventCode, level, message, CausePolicy.OPTIONAL, allowedFields);
    }

    public AgentEventCode eventCode() {
        return eventCode;
    }

    public AgentLogLevel level() {
        return level;
    }

    public String message() {
        return message;
    }

    public Set<AgentLogField> allowedFields() {
        return allowedFields;
    }

    public CausePolicy causePolicy() {
        return causePolicy;
    }

    @Override
    public String toString() {
        return """
            AgentLogEventDefinition[
              eventCode=%s,
              level=%s,
              causePolicy=%s,
              allowedFields=%s
            ]""".formatted(eventCode, level, causePolicy, allowedFields);
    }

    private static String requireNonBlank(String message) {
        if (StringUtils.isNullOrBlank(message)) {
            throw new IllegalArgumentException("message must not be blank");
        }

        return message;
    }

    private static Set<AgentLogField> copyAllowedFields(AgentLogField[] allowedFields) {
        Objects.requireNonNull(allowedFields, "allowedFields must not be null");
        if (allowedFields.length > MAX_FIELDS) {
            throw new IllegalArgumentException("allowedFields must contain at most " + MAX_FIELDS + " fields");
        }

        EnumSet<AgentLogField> fields = EnumSet.noneOf(AgentLogField.class);
        for (AgentLogField field : allowedFields) {
            if (!fields.add(Objects.requireNonNull(field, "allowedFields must not contain null"))) {
                throw new IllegalArgumentException("allowedFields must not contain duplicates");
            }
        }

        return Collections.unmodifiableSet(fields);
    }
}
