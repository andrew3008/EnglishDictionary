package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.INFO;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum ExporterLogEvent implements AgentLogEventType {

    EXPORT_COMPLETION_CALLBACK_FAILED(
            requiresCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "completion callback failed",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    EXPORT_ACCEPTED_SPAN_COUNT_UNAVAILABLE(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "OTLP response did not yield a usable accepted span count",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, INFO,
                    "Span export delegate succeeded after terminal export failures",
                    AgentLogField.EXPORTER_TYPE, AgentLogField.FAILURES
            )
    ),

    SAFE_EXPORT_THROWN(
            requiresCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "export() threw",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    SAFE_FLUSH_THROWN(
            requiresCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "flush() threw",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    SAFE_SHUTDOWN_THROWN(
            requiresCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "shutdown() threw",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    SAFE_EXPORT_RETURNED_NULL(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "export() returned null",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    SAFE_EXPORT_RETURNED_FAILURE(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "export() returned failure",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    SAFE_FLUSH_RETURNED_NULL(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "flush() returned null",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    SAFE_FLUSH_RETURNED_FAILURE(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "flush() returned failure",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    SAFE_SHUTDOWN_RETURNED_NULL(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "shutdown() returned null",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    SAFE_SHUTDOWN_RETURNED_FAILURE(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "shutdown() returned failure",
                    AgentLogField.EXPORTER_TYPE
            )
    ),

    TIMED_EXPORT_THROWN(
            requiresCause(
                    AgentEventCode.EXPORT_TIMED, WARN,
                    "exporter.export() threw"
            )
    ),

    TIMED_EXPORT_RETURNED_NULL(
            noCause(
                    AgentEventCode.EXPORT_TIMED, WARN,
                    "exporter.export() returned null"
            )
    ),

    TIMED_EXPORT_TIMED_OUT(
            noCause(
                    AgentEventCode.EXPORT_TIMED, WARN,
                    "exporter.export() timed out",
                    AgentLogField.TIMEOUT_MILLIS
            )
    ),

    TIMED_EXPORT_RETURNED_FAILURE(
            noCause(
                    AgentEventCode.EXPORT_TIMED, WARN,
                    "exporter.export() returned failure"
            )
    ),

    TIMED_DELEGATE_SLOT_HELD(
            noCause(
                    AgentEventCode.EXPORT_TIMED, WARN,
                    "Timed-out export still holds the delegate slot",
                    AgentLogField.HELD_MILLIS
            )
    ),

    TIMED_DELEGATE_SLOT_RELEASED(
            noCause(
                    AgentEventCode.EXPORT_TIMED, INFO,
                    "Timed-out export released the delegate slot",
                    AgentLogField.HELD_MILLIS, AgentLogField.OUTCOME
            )
    ),

    TIMED_SHUTDOWN_FAILED(
            requiresCause(
                    AgentEventCode.EXPORT_TIMED, WARN,
                    "exporter.shutdown() failed"
            )
    ),

    TIMED_SHUTDOWN_RETURNED_NULL(
            noCause(
                    AgentEventCode.EXPORT_TIMED, WARN,
                    "exporter.shutdown() returned null"
            )
    ),

    TIMED_SHUTDOWN_TIMED_OUT(
            noCause(
                    AgentEventCode.EXPORT_TIMED, WARN,
                    "exporter.shutdown() timed out",
                    AgentLogField.TIMEOUT_MILLIS
            )
    ),

    TIMED_SHUTDOWN_RETURNED_FAILURE(
            noCause(
                    AgentEventCode.EXPORT_TIMED, WARN,
                    "exporter.shutdown() returned failure"
            )
    );

    private final AgentLogEventDefinition definition;

    ExporterLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }
}
