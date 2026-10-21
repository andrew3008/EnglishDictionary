package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.optionalCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.ERROR;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.INFO;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum ExportPipelineLogEvent implements AgentLogEventType {

    FOREIGN_SAFE_SPAN_EXPORTER_REWRAP_REJECTED(
            noCause(
                    AgentEventCode.EXPORT_FACTORY, ERROR,
                    "Foreign SafeSpanExporter cannot be re-wrapped",
                    AgentLogField.TYPE
            )
    ),

    MULTIPLE_SPAN_EXPORTERS_FALLBACK(
            noCause(
                    AgentEventCode.EXPORT_FACTORY, WARN,
                    "Multiple SpanExporters; falling back to stock BatchSpanProcessor",
                    AgentLogField.COUNT
            )
    ),

    CAPTURED_SPAN_EXPORTER_NULL_FALLBACK(
            noCause(
                    AgentEventCode.EXPORT_FACTORY, WARN,
                    "Captured SpanExporter is null; falling back"
            )
    ),

    NON_BATCH_SPAN_PROCESSOR_FALLBACK(
            noCause(
                    AgentEventCode.EXPORT_FACTORY, WARN,
                    "Pipeline processor is not BatchSpanProcessor; falling back",
                    AgentLogField.TYPE
            )
    ),

    STOCK_BATCH_SPAN_PROCESSOR_SHUTDOWN_UNSUCCESSFUL(
            noCause(
                    AgentEventCode.EXPORT_FACTORY, WARN,
                    "Stock BatchSpanProcessor.shutdown() did not succeed"
            )
    ),

    DROP_OLDEST_EXPORT_PROCESSOR_ENABLED(
            noCause(
                    AgentEventCode.EXPORT_FACTORY, INFO,
                    "DROP_OLDEST export processor enabled",
                    AgentLogField.COUNT
            )
    ),

    COMPOSITE_PROCESSOR_LIFECYCLE_FAILED(
            optionalCause(
                    AgentEventCode.EXPORT_COMPOSITE_LIFECYCLE, WARN,
                    "Composite processor lifecycle failed",
                    AgentLogField.PHASE, AgentLogField.DELEGATE, AgentLogField.KIND
            )
    ),

    COMPOSITE_PROCESSOR_CALLBACK_FAILED(
            requiresCause(
                    AgentEventCode.EXPORT_CALLBACK, WARN,
                    "Composite processor callback failed",
                    AgentLogField.PHASE, AgentLogField.DELEGATE
            )
    ),

    EXPORT_WORKER_TERMINATED_ABNORMALLY(
            requiresCause(
                    AgentEventCode.EXPORT_WORKER, ERROR,
                    "Export worker terminated abnormally"
            )
    ),

    EXPORTER_SHUTDOWN_FAILED_AFTER_WORKER_DRAIN(
            requiresCause(
                    AgentEventCode.EXPORT_WORKER, ERROR,
                    "Exporter shutdown failed after worker drain completed"
            )
    ),

    FLUSH_COMPLETION_CALLBACK_FAILED(
            requiresCause(
                    AgentEventCode.EXPORT_CALLBACK, WARN,
                    "Flush completion callback failed"
            )
    ),

    SPAN_TO_DATA_CONVERSION_FAILED(
            requiresCause(
                    AgentEventCode.EXPORT_DROP_OLDEST, WARN,
                    "toSpanData() failed"
            )
    ),

    MAX_EXPORT_BATCH_SIZE_CLAMPED(
            noCause(
                    AgentEventCode.EXPORT_DROP_OLDEST, WARN,
                    "Exceeds maxQueueSize; clamped to queue size",
                    AgentLogField.NAME
            )
    );

    private final AgentLogEventDefinition definition;

    ExportPipelineLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }
}
