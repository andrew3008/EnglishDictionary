package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.optionalCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.DEBUG;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.ERROR;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.INFO;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum ScrubbingLogEvent implements AgentLogEventType {

    CRITICAL_SCRUBBING_RULE_FAILED_AT_EXPORT_BOUNDARY(
            noCause(
                    AgentEventCode.SCRUB_EXPORT_BOUNDARY, WARN,
                    "Critical scrubbing rule failed; collapsing all boundary surfaces"
            )
    ),

    SCRUBBING_RULE_COLLECTION_FAILED(
            requiresCause(
                    AgentEventCode.SCRUB_FAILURE, DEBUG,
                    "Scrubbing rule collection failed"
            )
    ),

    BUNDLED_SCRUBBING_RULE_LOADING_FAILED(
            requiresCause(
                    AgentEventCode.SCRUB_FAILURE, DEBUG,
                    "Bundled scrubbing rule loading failed"
            )
    ),

    BUNDLED_SCRUBBING_RULE_METADATA_VALIDATION_FAILED(
            requiresCause(
                    AgentEventCode.SCRUB_FAILURE, DEBUG,
                    "Bundled scrubbing rule metadata validation failed"
            )
    ),

    DUPLICATE_SCRUBBING_RULE_IGNORED(
            noCause(
                    AgentEventCode.SCRUB_INITIALIZED, DEBUG,
                    "Duplicate rule name ignored",
                    AgentLogField.NAME
            )
    ),

    SCRUBBING_RULES_UNREADABLE(
            optionalCause(
                    AgentEventCode.SCRUB_RULES_UNREADABLE, WARN,
                    "Scrubbing rules are unreadable",
                    AgentLogField.PATH
            )
    ),

    SCRUBBING_RULES_INITIALIZED(
            noCause(
                    AgentEventCode.SCRUB_INITIALIZED, INFO,
                    "Scrubbing rule initialized",
                    AgentLogField.BUILT_IN_RULES, AgentLogField.BUNDLED_SPI_RULES, AgentLogField.CUSTOM_RULES,
                    AgentLogField.KIND, AgentLogField.FAILED_PROVIDERS, AgentLogField.CLAMPED_PRIORITIES
            )
    ),

    SCRUBBING_PROVIDER_FAILED(
            noCause(
                    AgentEventCode.SCRUB_PROVIDER_FAILED, WARN,
                    "Scrubbing provider failed",
                    AgentLogField.NAME, AgentLogField.REASON
            )
    ),

    SCRUBBING_CIRCUIT_PROBE_FAILED(
            requiresCause(
                    AgentEventCode.SCRUB_CIRCUIT_OPEN, WARN,
                    "Circuit breaker probe failed; returning to OPEN",
                    AgentLogField.NAME, AgentLogField.COUNT, AgentLogField.FAILURE_TYPE
            )
    ),

    SCRUBBING_CIRCUIT_OPENED(
            requiresCause(
                    AgentEventCode.SCRUB_CIRCUIT_OPEN, WARN,
                    "Circuit breaker opened after consecutive failures",
                    AgentLogField.NAME, AgentLogField.COUNT, AgentLogField.FAILURE_TYPE
            )
    ),

    SCRUBBING_RULE_FAILURE_RECORDED(
            requiresCause(
                    AgentEventCode.SCRUB_CIRCUIT_OPEN, WARN,
                    "Recorded rule failure",
                    AgentLogField.NAME, AgentLogField.COUNT, AgentLogField.FAILURE_TYPE
            )
    ),

    SCRUBBING_PRIORITY_FLOOR_APPLIED(
            noCause(
                    AgentEventCode.SCRUB_PRIORITY_FLOOR, WARN,
                    "Scrubbing priority floor applied",
                    AgentLogField.NAME
            )
    ),

    EXTENSION_SCRUBBING_RULE_SKIPPED(
            optionalCause(
                    AgentEventCode.SCRUB_EXTENSION_SKIPPED, WARN,
                    "Extension scrubbing rule skipped",
                    AgentLogField.NAME, AgentLogField.REASON, AgentLogField.FAILURE_CONTEXT
            )
    ),

    EXTENSION_CLASSLOADER_CLOSE_FAILED(
            requiresCause(
                    AgentEventCode.SCRUB_EXTENSION_SKIPPED, WARN,
                    "Extension class loader close failed"
            )
    ),

    SCRUBBING_BOUNDARY_COLLAPSED(
            noCause(
                    AgentEventCode.SCRUB_BOUNDARY_COLLAPSE, ERROR,
                    "Scrubbing boundary collapsed"
            )
    ),

    SCRUB_PROCESSOR_SNAPSHOT_UNAVAILABLE(
            requiresCause(
                    AgentEventCode.SCRUB_FAILURE, ERROR,
                    "processor.snapshot_unavailable"
            )
    ),

    SCRUB_PROCESSOR_CALLBACK_FAILED(
            requiresCause(
                    AgentEventCode.SCRUB_FAILURE, ERROR,
                    "processor.callback_failed"
            )
    ),

    SCRUB_PROCESSOR_COLLAPSE_FAILED(
            requiresCause(
                    AgentEventCode.SCRUB_FAILURE, ERROR,
                    "processor.collapse_failed"
            )
    ),

    SCRUB_BOUNDARY_FAILED(
            requiresCause(
                    AgentEventCode.SCRUB_FAILURE, ERROR,
                    "boundary.scrub_failed"
            )
    ),

    SCRUB_BOUNDARY_COLLAPSE_FAILED_DROPPING_SPAN(
            requiresCause(
                    AgentEventCode.SCRUB_FAILURE, ERROR,
                    "boundary.collapse_failed_dropping_span"
            )
    ),

    SCRUB_BOUNDARY_BATCH_PREPARATION_FAILED(
            requiresCause(
                    AgentEventCode.SCRUB_FAILURE, ERROR,
                    "BOUNDARY_BATCH_PREPARATION_FAILED"
            )
    ),

    SCRUBBING_HMAC_KEY_MISSING(
            noCause(
                    AgentEventCode.SCRUB_HMAC_MISSING, WARN,
                    "Scrubbing HMAC key is not set"
            )
    ),

    SCRUBBING_FAIL_CLOSED_MASK_APPLIED(
            noCause(
                    AgentEventCode.SCRUB_RULE_FAILED, WARN,
                    "Applying fail-closed mask",
                    AgentLogField.ATTRIBUTE
            )
    ),

    UNSUPPORTED_SPAN_ATTRIBUTE_TYPE(
            noCause(
                    AgentEventCode.SCRUB_UNSUPPORTED_TYPE, WARN,
                    "Unsupported attribute type",
                    AgentLogField.TYPE
            )
    ),

    IP_PREFIX_TRUNCATION_FALLBACK_TO_FULL_MASK(
            noCause(
                    AgentEventCode.SCRUB_RULE_FAILED, WARN,
                    "IP prefix truncation failed; falling back to full mask",
                    AgentLogField.REASON
            )
    );

    private final AgentLogEventDefinition definition;

    ScrubbingLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }
}
