package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.ERROR;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum ValidationLogEvent implements AgentLogEventType {

    VALIDATION_DISABLED(
            noCause(
                    AgentEventCode.VALID_POLICY, WARN,
                    "Validation DISABLED"
            )
    ),

    REQUIRED_SPAN_ATTRIBUTES_MISSING_ERROR(
            noCause(
                    AgentEventCode.VALID_MISSING_ATTRIBUTES, ERROR,
                    "Required span attributes are missing",
                    AgentLogField.COUNT, AgentLogField.MISSING_KEYS
            )
    ),

    REQUIRED_SPAN_ATTRIBUTES_MISSING_WARN(
            noCause(
                    AgentEventCode.VALID_MISSING_ATTRIBUTES, WARN,
                    "Required span attributes are missing",
                    AgentLogField.COUNT, AgentLogField.MISSING_KEYS
            )
    );

    private final AgentLogEventDefinition definition;

    ValidationLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }
}
