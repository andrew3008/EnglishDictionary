package space.br1440.platform.tracing.otel.javaagent.logging.schema;

import java.util.Objects;

import space.br1440.platform.common.util.StringUtils;

public enum AgentLogField {

    ACQUIRED_PERMIT_COUNT("acquiredPermitCount", Kind.LONG),
    ACCEPTED_SEQUENCE("acceptedSequence", Kind.LONG),
    ATTEMPT_ID("attemptId", Kind.STRING),
    ATTRIBUTE("attribute", Kind.STRING),
    BUILT_IN_RULES("builtInRules", Kind.LONG),
    BUNDLED_SPI_RULES("bundledSpiRules", Kind.LONG),
    CAPACITY("capacity", Kind.LONG),
    CHANGED_LEAVES("changedLeaves", Kind.STRING, true),
    CLAMPED_PRIORITIES("clampedPriorities", Kind.LONG),
    COUNT("count", Kind.LONG),
    CUSTOM_RULES("customRules", Kind.LONG),
    DELEGATE("delegate", Kind.STRING),
    ENVIRONMENT("environment", Kind.STRING),
    EVICTED("evicted", Kind.LONG),
    EXTENSION_VERSION("extensionVersion", Kind.STRING),
    FAILED_PROVIDERS("failedProviders", Kind.LONG),
    EXPORTER_TYPE("exporterType", Kind.STRING),
    FAILURE_CODE("failureCode", Kind.STRING),
    FAILURE_CONTEXT("failureContext", Kind.STRING),
    FAILURE_SAMPLE("failureSample", Kind.STRING),
    FAILURE_TYPE("failureType", Kind.STRING),
    FAILURES("failures", Kind.LONG),
    HELD_MILLIS("heldMillis", Kind.LONG),
    KIND("kind", Kind.STRING),
    LIFECYCLE("lifecycle", Kind.ENUM),
    LIMIT("limit", Kind.LONG),
    MAX_TRACKED_SPANS("maxTrackedSpans", Kind.LONG),
    MAX_TRACKED_SPANS_PER_TRACE("maxTrackedSpansPerTrace", Kind.LONG),
    MESSAGE_CODE("messageCode", Kind.ENUM),
    MISSING_KEYS("missingKeys", Kind.STRING),
    NAME("name", Kind.STRING),
    OFFENDING_ARGUMENTS("offendingArguments", Kind.STRING, true),
    OPERATION("operation", Kind.STRING),
    OUTCOME("outcome", Kind.ENUM),
    PATH("path", Kind.STRING),
    PHASE("phase", Kind.STRING),
    PROPERTY_NAME("propertyName", Kind.STRING),
    PROFILE("profile", Kind.STRING),
    PROTOCOL_VERSION("protocolVersion", Kind.LONG),
    REASON("reason", Kind.STRING),
    REJECTED("rejected", Kind.LONG),
    REMEDIATION("remediation", Kind.STRING),
    REQUESTED_RELEASE_COUNT("requestedReleaseCount", Kind.LONG),
    RESOURCE("resource", Kind.STRING),
    RESOURCE_LABEL("resourceLabel", Kind.STRING),
    SAMPLE_SPAN_ID("sampleSpanId", Kind.STRING),
    SAMPLE_TRACE_ID("sampleTraceId", Kind.STRING),
    SERVICE("service", Kind.STRING),
    SOURCE("source", Kind.STRING),
    SPAN_ID("spanId", Kind.STRING),
    SPAN_TIMEOUT_ATTEMPTS("spanTimeoutAttempts", Kind.LONG),
    STAGE("stage", Kind.STRING),
    SUBPHASE("subphase", Kind.STRING),
    TIMEOUT_MILLIS("timeoutMillis", Kind.LONG),
    TRACE_ID("traceId", Kind.STRING),
    TRACE_TIMEOUT_ATTEMPTS("traceTimeoutAttempts", Kind.LONG),
    TYPE("type", Kind.STRING),
    VERSION("version", Kind.STRING),
    WRITER("writer", Kind.ENUM);

    private final String wireName;
    private final Kind kind;
    private final boolean allowsBlank;

    AgentLogField(String wireName, Kind kind) {
        this(wireName, kind, false);
    }

    AgentLogField(String wireName, Kind kind, boolean allowsBlank) {
        if (StringUtils.isNullOrBlank(wireName)) {
            throw new IllegalArgumentException("wireName must not be blank");
        }

        this.wireName = wireName;
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        if (allowsBlank && kind != Kind.STRING) {
            throw new IllegalArgumentException("Blank values can only be allowed for STRING fields");
        }
        this.allowsBlank = allowsBlank;
    }

    public String wireName() {
        return wireName;
    }

    public Kind kind() {
        return kind;
    }

    public boolean allowsBlank() {
        return allowsBlank;
    }

    public enum Kind {
        STRING,
        LONG,
        ENUM
    }
}
