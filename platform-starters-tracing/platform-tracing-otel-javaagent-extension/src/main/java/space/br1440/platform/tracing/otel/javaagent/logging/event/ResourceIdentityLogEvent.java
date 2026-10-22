package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.INFO;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum ResourceIdentityLogEvent implements AgentLogEventType {

    UPSTREAM_SERVICE_EXTRACTION_FAILED(
            noCause(
                    AgentEventCode.BOOT_UPSTREAM_SERVICE, WARN,
                    "Failed to extract upstream service name"
            )
    ),

    RESOURCE_CUSTOMIZATION_FAILED(
            requiresCause(
                    AgentEventCode.BOOT_RESOURCE_IDENTITY, WARN,
                    "Resource customization failed"
            )
    ),

    BUILD_INFO_UNREADABLE(
            requiresCause(
                    AgentEventCode.BOOT_RESOURCE_IDENTITY, WARN,
                    "Application build info is unreadable",
                    AgentLogField.RESOURCE
            )
    ),

    RESOURCE_IDENTITY_RESOLVED(
            noCause(
                    AgentEventCode.BOOT_RESOURCE_IDENTITY, INFO,
                    "Resource identity resolved",
                    AgentLogField.SERVICE, AgentLogField.ENVIRONMENT, AgentLogField.VERSION
            )
    ),

    RESOURCE_IDENTITY_INCOMPLETE(
            noCause(
                    AgentEventCode.BOOT_RESOURCE_IDENTITY, WARN,
                    "Resource identity incomplete",
                    AgentLogField.SERVICE, AgentLogField.ENVIRONMENT, AgentLogField.VERSION,
                    AgentLogField.MISSING_KEYS
            )
    );

    private final AgentLogEventDefinition definition;

    ResourceIdentityLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }
}
