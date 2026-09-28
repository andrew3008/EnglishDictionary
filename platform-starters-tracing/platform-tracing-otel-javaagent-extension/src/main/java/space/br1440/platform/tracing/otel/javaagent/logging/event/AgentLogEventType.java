package space.br1440.platform.tracing.otel.javaagent.logging.event;

import java.util.Set;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public sealed interface AgentLogEventType permits
        BootstrapLogEvent,
        ResourceIdentityLogEvent,
        ControlPlaneLogEvent,
        ExporterLogEvent,
        ExportPipelineLogEvent,
        SpanTimeoutLogEvent,
        ScrubbingLogEvent,
        ValidationLogEvent,
        SamplingLogEvent,
        LogThrottlingLogEvent {

    String name();

    AgentLogEventDefinition definition();

    default AgentEventCode eventCode() {
        return definition().eventCode();
    }

    default AgentLogLevel level() {
        return definition().level();
    }

    default String message() {
        return definition().message();
    }

    default Set<AgentLogField> allowedFields() {
        return definition().allowedFields();
    }
}
