package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.ERROR;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum LogThrottlingLogEvent implements AgentLogEventType {

    RATE_WARNINGS_THROTTLED(
            noCause(
                    AgentEventCode.RATE_THROTTLED, WARN,
                    "Agent logging is throttled",
                    AgentLogField.KIND
            )
    ),

    RATE_ERRORS_THROTTLED(
            noCause(
                    AgentEventCode.RATE_THROTTLED, ERROR,
                    "Agent logging is throttled",
                    AgentLogField.KIND
            )
    );

    private final AgentLogEventDefinition definition;

    LogThrottlingLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }
}
