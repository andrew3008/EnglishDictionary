package space.br1440.platform.tracing.otel.javaagent.logging;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public interface AgentLogFields {

    AgentLogFields putString(AgentLogField field, String value);

    AgentLogFields putLong(AgentLogField field, long value);

    AgentLogFields putEnum(AgentLogField field, Enum<?> value);

    AgentLogFields cause(Throwable cause);

}
