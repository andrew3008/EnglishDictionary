package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class JmxObservationWireContract {

    public static final int SCHEMA_VERSION = 1;

    // -- Общий каркас (frame) ---------------------------------------------------
    public static final String ITEM_SCHEMA_VERSION = "schemaVersion";
    public static final String ITEM_CAPTURE_SEQUENCE = "captureSequence";
    public static final String ITEM_AGENT_INSTANCE_ID = "agentInstanceId";
    public static final String ITEM_CAPTURE_STARTED_AT = "captureStartedAt";
    public static final String ITEM_CAPTURE_COMPLETED_AT = "captureCompletedAt";

    // -- Каркас per-owner sub-composite ------------------------------------------
    public static final String ITEM_OWNER_PRESENT = "ownerPresent";
    public static final String ITEM_OWNER_GENERATION = "ownerGeneration";
    public static final String ITEM_OWNER_CAPTURED_OFFSET_NANOS = "ownerCapturedOffsetNanos";

    // -- Sampling ---------------------------------------------------------------

    public static final String SAMPLING_COMPOSITE_TYPE_NAME = "space.br1440.platform.tracing.SamplingObservation";

    public static final String SAMPLING_ITEM_SAMPLER_ENABLED = "samplerEnabled";
    public static final String SAMPLING_ITEM_SAMPLING_RATIO = "samplingRatio";
    public static final String SAMPLING_ITEM_ROUTE_RATIOS = "routeRatios";
    public static final String SAMPLING_ITEM_DECISION_COUNTS = "samplerDecisionCounts";
    public static final String SAMPLING_ITEM_CONFIG_LAST_UPDATED_SOURCE = "samplingConfigLastUpdatedSource";

    public static final String SAMPLING_ITEM_SAMPLER_OWNER_PRESENT = "samplerOwnerPresent";
    public static final String SAMPLING_ITEM_SAMPLER_OWNER_GENERATION = "samplerOwnerGeneration";
    public static final String SAMPLING_ITEM_SAMPLER_OWNER_CAPTURED_OFFSET_NANOS = "samplerOwnerCapturedOffsetNanos";

    // -- Scrubbing ---------------------------------------------------------------

    public static final String SCRUBBING_COMPOSITE_TYPE_NAME = "space.br1440.platform.tracing.ScrubbingObservation";

    public static final String SCRUBBING_ITEM_ENABLED = "scrubbingEnabled";
    public static final String SCRUBBING_ITEM_POLICY_VERSION = "policyVersion";
    public static final String SCRUBBING_ITEM_RULES_LOADED = "rulesLoaded";
    public static final String SCRUBBING_ITEM_CIRCUIT_BREAKERS_OPEN = "circuitBreakersOpen";
    public static final String SCRUBBING_ITEM_METRICS = "scrubbingMetrics";

    // -- Validation ---------------------------------------------------------------

    public static final String VALIDATION_COMPOSITE_TYPE_NAME = "space.br1440.platform.tracing.ValidationObservation";

    public static final String VALIDATION_ITEM_ENABLED = "validationEnabled";
    public static final String VALIDATION_ITEM_STRICT = "validationStrict";
    public static final String VALIDATION_ITEM_STRICT_RUNTIME_ALLOWED = "validationStrictRuntimeAllowed";
    public static final String VALIDATION_ITEM_CONFIG_VERSION = "validationConfigVersion";
    public static final String VALIDATION_ITEM_CONFIG_LAST_UPDATED_SOURCE = "validationConfigLastUpdatedSource";

    // -- Export ---------------------------------------------------------------

    public static final String EXPORT_COMPOSITE_TYPE_NAME = "space.br1440.platform.tracing.ExportObservation";

    public static final String EXPORT_ITEM_PROCESSOR = "processor";
    public static final String EXPORT_ITEM_EXPORTER = "exporter";

    public static final String EXPORT_PROCESSOR_VALUES_TYPE_NAME = "space.br1440.platform.tracing.ExportProcessorValues";
    public static final String EXPORT_PROCESSOR_ITEM_QUEUE_CAPACITY = "queueCapacity";
    public static final String EXPORT_PROCESSOR_ITEM_QUEUE_SIZE = "queueSize";
    public static final String EXPORT_PROCESSOR_ITEM_DROPPED_OVERFLOW_TOTAL = "droppedOverflowTotal";
    public static final String EXPORT_PROCESSOR_ITEM_DROPPED_AFTER_SHUTDOWN_TOTAL = "droppedAfterShutdownTotal";
    public static final String EXPORT_PROCESSOR_ITEM_FAILURES_TOTAL = "failuresTotal";
    public static final String EXPORT_PROCESSOR_ITEM_TIMEOUTS_TOTAL = "timeoutsTotal";

    public static final String EXPORT_EXPORTER_VALUES_TYPE_NAME = "space.br1440.platform.tracing.SafeExporterValues";
    public static final String EXPORT_EXPORTER_ITEM_ENABLED = "exportEnabled";
    public static final String EXPORT_EXPORTER_ITEM_LAST_DURATION_NANOS = "lastExportDurationNanos";
    public static final String EXPORT_EXPORTER_ITEM_METRICS = "safeExporterMetrics";

    // -- ProcessorMetrics ---------------------------------------------------------------

    public static final String PROCESSOR_METRICS_COMPOSITE_TYPE_NAME = "space.br1440.platform.tracing.ProcessorMetricsObservation";

    public static final String PROCESSOR_METRICS_ITEM_WATCHDOG = "watchdog";
    public static final String PROCESSOR_METRICS_ITEM_COMPOSITE = "composite";
    public static final String PROCESSOR_METRICS_ITEM_METRICS = "metrics";

    public static final String PROCESSOR_METRICS_WATCHDOG_VALUES_TYPE_NAME = "space.br1440.platform.tracing.ProcessorWatchdogValues";
    public static final String PROCESSOR_METRICS_WATCHDOG_ITEM_FORCED_SPAN_CLOSES = "forcedSpanCloses";
    public static final String PROCESSOR_METRICS_WATCHDOG_ITEM_FORCED_TRACE_CLOSES = "forcedTraceCloses";
    public static final String PROCESSOR_METRICS_WATCHDOG_ITEM_ACTIVE_SPAN_COUNT = "activeSpanCount";
    public static final String PROCESSOR_METRICS_WATCHDOG_ITEM_ACTIVE_TRACE_COUNT = "activeTraceCount";

    public static final String PROCESSOR_METRICS_COMPOSITE_VALUES_TYPE_NAME = "space.br1440.platform.tracing.ProcessorCompositeValues";
    public static final String PROCESSOR_METRICS_COMPOSITE_ITEM_ERRORS_TOTAL = "processorErrorsTotal";
    public static final String PROCESSOR_METRICS_COMPOSITE_ITEM_ERRORS_BY_NAME = "processorErrorsByName";

    public static final String PROCESSOR_METRICS_METRICS_VALUES_TYPE_NAME = "space.br1440.platform.tracing.ProcessorMetricsValues";
    public static final String PROCESSOR_METRICS_METRICS_ITEM_DROPPED_ATTRIBUTES_TOTAL = "droppedAttributesTotal";
    public static final String PROCESSOR_METRICS_METRICS_ITEM_DROPPED_EVENTS_TOTAL = "droppedEventsTotal";
    public static final String PROCESSOR_METRICS_METRICS_ITEM_DROPPED_LINKS_TOTAL = "droppedLinksTotal";

    // -- Diagnostics ---------------------------------------------------------------

    public static final String DIAGNOSTICS_COMPOSITE_TYPE_NAME = "space.br1440.platform.tracing.DiagnosticsObservation";

    public static final String DIAGNOSTICS_ITEM_INVALID_CONFIG_COUNT = "invalidConfigCount";
    public static final String DIAGNOSTICS_ITEM_CONFIG_RELOAD_METRICS = "configReloadMetrics";
    public static final String DIAGNOSTICS_ITEM_CONFIG_AUDIT_TRAIL = "configAuditTrail";
    public static final String DIAGNOSTICS_ITEM_SAFE_WRAPPER_METRICS = "safeWrapperMetrics";
    public static final String DIAGNOSTICS_ITEM_PLATFORM_LOG_LEVEL = "platformLogLevel";
    public static final String DIAGNOSTICS_ITEM_PROPAGATION_ENABLED = "propagationEnabled";

}
