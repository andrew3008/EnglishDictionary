package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.DEBUG;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.INFO;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

/**
 * События разрешения resource identity: hostname, upstream service, атрибуты ресурса.
 * Домен кодов: {@code BOOT_HOSTNAME_*, BOOT_RESOURCE_*, BOOT_UPSTREAM_*}.
 */
public enum ResourceIdentityLogEvent implements AgentLogEventType {

    UPSTREAM_SERVICE_EXTRACTION_FAILED(
            noCause(
                    AgentEventCode.BOOT_UPSTREAM_SERVICE, WARN,
                    "Failed to extract upstream service name"
            )
    ),

    HOSTNAME_RESOLUTION_FAILED(
            requiresCause(
                    AgentEventCode.BOOT_HOSTNAME_FAILED, DEBUG,
                    "Hostname resolution failed",
                    AgentLogField.FAILURE_TYPE
            )
    ),

    HOSTNAME_RESOLUTION_TIMED_OUT(
            noCause(
                    AgentEventCode.BOOT_HOSTNAME_TIMEOUT, WARN,
                    "Hostname resolution timed out",
                    AgentLogField.TIMEOUT_MS
            )
    ),

    RESOURCE_CUSTOMIZATION_FAILED(
            requiresCause(
                    AgentEventCode.BOOT_RESOURCE_IDENTITY, WARN,
                    "Resource customization failed"
            )
    ),

    RESOURCE_IDENTITY_RESOLVED(
            noCause(
                    AgentEventCode.BOOT_RESOURCE_IDENTITY, INFO,
                    "Resource identity resolved",
                    AgentLogField.SERVICE, AgentLogField.ENVIRONMENT, AgentLogField.VERSION, AgentLogField.KIND,
                    AgentLogField.COUNT
            )
    ),

    RESOURCE_IDENTITY_INCOMPLETE(
            noCause(
                    AgentEventCode.BOOT_RESOURCE_IDENTITY, WARN,
                    "Resource identity incomplete",
                    AgentLogField.SERVICE, AgentLogField.ENVIRONMENT, AgentLogField.VERSION, AgentLogField.KIND,
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
