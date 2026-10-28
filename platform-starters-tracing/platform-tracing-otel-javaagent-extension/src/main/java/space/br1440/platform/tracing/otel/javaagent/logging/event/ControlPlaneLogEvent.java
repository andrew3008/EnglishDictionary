package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.DEBUG;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.ERROR;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.INFO;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public enum ControlPlaneLogEvent implements AgentLogEventType {

    JMX_SETTINGS_STATUS_CAPTURE_FAILED(
            requiresCause(
                    AgentEventCode.CTRL_PLANE, WARN,
                    "JMX settings status capture failed"
            )
    ),

    JMX_SETTINGS_MUTATION_FAILED(
            requiresCause(
                    AgentEventCode.CTRL_WRITER, WARN,
                    "JMX settings mutation failed",
                    AgentLogField.PHASE, AgentLogField.ATTEMPT_ID
            )
    ),

    JMX_SETTINGS_POLICY_REJECTED(
            noCause(
                    AgentEventCode.CTRL_WRITER, WARN,
                    "JMX settings policy rejected",
                    AgentLogField.PHASE,
                    AgentLogField.ATTEMPT_ID,
                    AgentLogField.MESSAGE_CODE,
                    AgentLogField.PROPERTY_NAME
            )
    ),

    TRACING_SETTINGS_UPDATE_FAILED(
            requiresCause(
                    AgentEventCode.CTRL_UPDATE, WARN,
                    "Tracing settings update failed",
                    AgentLogField.ATTEMPT_ID
            )
    ),

    DIAGNOSTICS_LOG_LEVEL_APPLY_FAILED(
            requiresCause(
                    AgentEventCode.CTRL_DIAGNOSTICS_LEVEL, ERROR,
                    "Diagnostics log level apply failed",
                    AgentLogField.ATTEMPT_ID
            )
    ),

    TRACING_SETTINGS_UPDATE_APPLIED(settingsUpdate(INFO, "Tracing settings update applied")),

    TRACING_SETTINGS_UPDATE_REJECTED(settingsUpdate(WARN, "Tracing settings update rejected")),

    TRACING_SETTINGS_UPDATE_UNCHANGED(settingsUpdate(DEBUG, "Tracing settings update unchanged")),

    TRACING_SETTINGS_UPDATE_COMMIT_FAILED(settingsUpdate(ERROR, "Tracing settings update commit failed")),

    JMX_OBSERVATION_SNAPSHOT_PUBLICATION_FAILED(
            requiresCause(
                    AgentEventCode.CTRL_PLANE, WARN,
                    "JMX observation snapshot publication failed",
                    AgentLogField.SOURCE
            )
    ),

    JMX_OBSERVATION_SNAPSHOT_PUBLICATION_REJECTED(
            requiresCause(
                    AgentEventCode.CTRL_PLANE, WARN,
                    "JMX observation snapshot publication request was rejected"
            )
    ),

    JMX_OBSERVATION_SNAPSHOT_PUBLISHER_STOP_TIMEOUT(
            noCause(
                    AgentEventCode.CTRL_PLANE, WARN,
                    "JMX observation snapshot publisher did not stop within the shutdown timeout"
            )
    ),

    JMX_OBSERVATION_SNAPSHOT_PUBLISHER_STOP_INTERRUPTED(
            requiresCause(
                    AgentEventCode.CTRL_PLANE, WARN,
                    "Interrupted while stopping JMX observation snapshot publisher"
            )
    ),

    MBEAN_BATCH_REGISTRATION_FAILED(
            requiresCause(
                    AgentEventCode.CTRL_MBEAN_BATCH, ERROR,
                    "MBean batch registration failed",
                    AgentLogField.COUNT
            )
    );

    private final AgentLogEventDefinition definition;

    ControlPlaneLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }

    private static AgentLogEventDefinition settingsUpdate(AgentLogLevel level, String message) {
        return noCause(
                AgentEventCode.CTRL_UPDATE, level,
                message,
                AgentLogField.ATTEMPT_ID,
                AgentLogField.WRITER,
                AgentLogField.OUTCOME,
                AgentLogField.MESSAGE_CODE,
                AgentLogField.ACCEPTED_SEQUENCE,
                AgentLogField.CHANGED_LEAVES
        );
    }
}
