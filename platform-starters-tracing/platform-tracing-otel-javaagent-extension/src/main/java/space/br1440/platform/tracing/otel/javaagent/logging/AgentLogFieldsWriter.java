package space.br1440.platform.tracing.otel.javaagent.logging;

@FunctionalInterface
public interface AgentLogFieldsWriter {

    void write(AgentLogFields fields);
}
