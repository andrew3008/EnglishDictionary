package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.optionalCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum SamplingLogEvent implements AgentLogEventType {

    SAMPLER_FAILED_CLOSED_TO_DROP(
            optionalCause(
                    AgentEventCode.SAMPLE_FALLBACK, WARN,
                    "Sampler failed closed to DROP",
                    AgentLogField.REASON
            )
    );

    private final AgentLogEventDefinition definition;

    SamplingLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }
}
