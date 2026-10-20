package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.DEBUG;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.INFO;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum SpanTimeoutLogEvent implements AgentLogEventType {

    GLOBAL_TRACKED_SPAN_CAPACITY_BELOW_PER_TRACE_CAPACITY(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Global tracked-span capacity is below per-trace capacity; effective single-trace capacity is bounded by the global budget",
                    AgentLogField.MAX_TRACKED_SPANS, AgentLogField.MAX_TRACKED_SPANS_PER_TRACE
            )
    ),

    SPAN_TIMEOUT_PERMIT_RELEASE_VIOLATION(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT_PERMIT_VIOLATION, WARN,
                    "Permit release exceeded the acquired permit count",
                    AgentLogField.KIND, AgentLogField.ACQUIRED_PERMIT_COUNT, AgentLogField.REQUESTED_RELEASE_COUNT,
                    AgentLogField.OPERATION, AgentLogField.TRACE_ID
            )
    ),

    SPAN_TIMEOUT_PER_TRACE_CAPACITY_REACHED(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Span timeout rejected span tracking: per-trace tracked-span capacity reached",
                    AgentLogField.REJECTED, AgentLogField.LIMIT
            )
    ),

    SPAN_TIMEOUT_GLOBAL_SPAN_CAPACITY_REACHED(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Span timeout rejected span tracking: global tracked-span capacity reached",
                    AgentLogField.REJECTED, AgentLogField.LIMIT
            )
    ),

    SPAN_TIMEOUT_TRACKED_TRACE_CAPACITY_REACHED(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Span timeout rejected span tracking: tracked-trace capacity reached",
                    AgentLogField.REJECTED, AgentLogField.LIMIT
            )
    ),

    SPAN_TIMEOUT_EMPTY_TRACE_STATES_EVICTED(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Span timeout evicted empty trace states under pressure",
                    AgentLogField.EVICTED, AgentLogField.CAPACITY
            )
    ),

    UNFINISHED_SPANS_AT_SDK_SHUTDOWN(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, INFO,
                    "Unfinished spans remain at SDK shutdown",
                    AgentLogField.COUNT
            )
    ),

    SPAN_TIMEOUT_SCAN_STAGE_FAILED(
            requiresCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Span-timeout scan stage failed",
                    AgentLogField.STAGE
            )
    ),

    SPAN_TIMEOUT_FORCE_END_FAILED(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, DEBUG,
                    "Span timeout failed to force-end span",
                    AgentLogField.TRACE_ID, AgentLogField.SPAN_ID, AgentLogField.REASON, AgentLogField.FAILURE_TYPE
            )
    ),

    SPAN_TIMEOUT_FORCE_ENDED(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, DEBUG,
                    "Span timeout force-ended span",
                    AgentLogField.TRACE_ID, AgentLogField.SPAN_ID, AgentLogField.REASON
            )
    ),

    SPAN_TIMEOUT_FORCE_END_SUMMARY(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, INFO,
                    "Span timeout force-ended spans",
                    AgentLogField.SPAN_TIMEOUT_ATTEMPTS, AgentLogField.TRACE_TIMEOUT_ATTEMPTS, AgentLogField.FAILURES,
                    AgentLogField.SAMPLE_TRACE_ID, AgentLogField.SAMPLE_SPAN_ID
            )
    ),

    SPAN_TIMEOUT_FORCE_END_SUMMARY_WITH_FAILURES(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Span timeout force-ended spans with failures",
                    AgentLogField.SPAN_TIMEOUT_ATTEMPTS, AgentLogField.TRACE_TIMEOUT_ATTEMPTS, AgentLogField.FAILURES,
                    AgentLogField.SAMPLE_TRACE_ID, AgentLogField.SAMPLE_SPAN_ID, AgentLogField.FAILURE_SAMPLE
            )
    ),

    SPAN_TIMEOUT_SCANNER_PREVIOUSLY_TERMINATED_ABNORMALLY(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Span-timeout scanner had terminated abnormally before shutdown"
            )
    ),

    SPAN_TIMEOUT_SCANNER_STOP_INTERRUPTED(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Interrupted while waiting for the span-timeout scanner to stop"
            )
    ),

    SPAN_TIMEOUT_SCANNER_STOP_TIMED_OUT(
            noCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Span-timeout scanner did not stop within the shutdown timeout",
                    AgentLogField.TIMEOUT_MILLIS
            )
    ),

    SPAN_TIMEOUT_SCANNER_TERMINATED_ABNORMALLY(
            requiresCause(
                    AgentEventCode.EXPORT_SPAN_TIMEOUT, WARN,
                    "Span-timeout scanner terminated abnormally"
            )
    );

    private final AgentLogEventDefinition definition;

    SpanTimeoutLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }
}
