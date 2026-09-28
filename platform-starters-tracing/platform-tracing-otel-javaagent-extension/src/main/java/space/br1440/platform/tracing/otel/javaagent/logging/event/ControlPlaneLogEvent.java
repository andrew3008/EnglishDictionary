package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.ERROR;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

/**
 * События control plane: JMX settings, observation snapshots, регистрация MBean.
 * Домен кодов: {@code CTRL_*}.
 */
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

    TRACING_SETTINGS_UPDATE_FAILED(
            requiresCause(
                    AgentEventCode.CTRL_UPDATE, WARN,
                    "Tracing settings update failed",
                    AgentLogField.ATTEMPT_ID
            )
    ),

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
}
