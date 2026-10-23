package space.br1440.platform.tracing.otel.javaagent.logging;

@FunctionalInterface
public interface AgentLogFieldsWriter {

    AgentLogFieldsWriter NONE = fields -> {
    };

    void write(AgentLogFields fields);

}
