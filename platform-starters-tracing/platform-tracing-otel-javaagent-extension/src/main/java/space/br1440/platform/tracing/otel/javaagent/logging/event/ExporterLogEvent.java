package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.optionalCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.INFO;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum ExporterLogEvent implements AgentLogEventType {

    EXPORT_COMPLETION_CALLBACK_FAILED(
            requiresCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "completion callback failed"
            )
    ),

    EXPORT_ACCEPTED_SPAN_COUNT_UNAVAILABLE(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "OTLP response did not yield a usable accepted span count"
            )
    ),

    SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, INFO,
                    "Span export delegate succeeded after terminal export failures",
                    AgentLogField.FAILURES
            )
    ),

    SAFE_EXPORT_OVERSIZED_SPAN_DROPPED(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "Oversized spans were dropped before OTLP export",
                    AgentLogField.COUNT, AgentLogField.LIMIT_BYTES
            )
    ),

    SAFE_EXPORT_SIZE_MEASUREMENT_FAILED(
            requiresCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "Span size measurement failed before OTLP export",
                    AgentLogField.COUNT
            )
    ),

    SAFE_EXPORT_THROWN(
            requiresCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "export() threw"
            )
    ),

    SAFE_FLUSH_THROWN(
            requiresCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "flush() threw"
            )
    ),

    SAFE_SHUTDOWN_THROWN(
            requiresCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "shutdown() threw"
            )
    ),

    SAFE_EXPORT_RETURNED_FAILURE(
            optionalCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "export() returned failure"
            )
    ),

    SAFE_FLUSH_RETURNED_FAILURE(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "flush() returned failure"
            )
    ),

    SAFE_SHUTDOWN_RETURNED_FAILURE(
            noCause(
                    AgentEventCode.EXPORT_SAFE_EXPORTER, WARN,
                    "shutdown() returned failure"
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
