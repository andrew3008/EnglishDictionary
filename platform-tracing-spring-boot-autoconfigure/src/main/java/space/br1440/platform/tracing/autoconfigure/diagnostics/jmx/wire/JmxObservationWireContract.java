package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire;

import java.util.List;
import java.util.Map;

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

    public enum KeyCardinality {
        FIXED_ENUM,
        BOUNDED_ALLOWLIST,
        DYNAMIC_NOT_EXPORTED
    }

    public enum ValueSemantics {
        CUMULATIVE,
        POINT_IN_TIME
    }

    public record DynamicMapKey(String key, KeyCardinality cardinality, ValueSemantics semantics) {
    }

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

    public static final List<DynamicMapKey> SAMPLING_DECISION_COUNTS_CLASSIFICATION = List.of(
            new DynamicMapKey("RECORD_AND_SAMPLE:force_header", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("RECORD_AND_SAMPLE:qa_trace", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("RECORD_AND_SAMPLE:parent_sampled", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("RECORD_AND_SAMPLE:route_ratio", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("RECORD_AND_SAMPLE:global_ratio", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("DROP:kill_switch", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("DROP:drop_path", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("DROP:parent_drop", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("DROP:route_ratio_drop", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("DROP:global_ratio_drop", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("DROP:fallback_drop", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE));

    public static final List<DynamicMapKey> SAMPLING_ROUTE_RATIOS_CLASSIFICATION = List.of(
            new DynamicMapKey("*", KeyCardinality.DYNAMIC_NOT_EXPORTED, ValueSemantics.POINT_IN_TIME));

    // -- Scrubbing ---------------------------------------------------------------

    public static final String SCRUBBING_COMPOSITE_TYPE_NAME = "space.br1440.platform.tracing.ScrubbingObservation";

    public static final String SCRUBBING_ITEM_ENABLED = "scrubbingEnabled";
    public static final String SCRUBBING_ITEM_POLICY_VERSION = "policyVersion";
    public static final String SCRUBBING_ITEM_RULES_LOADED = "rulesLoaded";
    public static final String SCRUBBING_ITEM_CIRCUIT_BREAKERS_OPEN = "circuitBreakersOpen";
    public static final String SCRUBBING_ITEM_METRICS = "scrubbingMetrics";

    public static final List<DynamicMapKey> SCRUBBING_METRICS_CLASSIFICATION = List.of(
            new DynamicMapKey("drop", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("hash", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("mask", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("truncate", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("failures", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("hash_missing_key", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("circuit_breakers.failures.total", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("rules.loaded", KeyCardinality.FIXED_ENUM, ValueSemantics.POINT_IN_TIME),
            new DynamicMapKey("circuit_breakers.open", KeyCardinality.FIXED_ENUM, ValueSemantics.POINT_IN_TIME),
            new DynamicMapKey("enabled", KeyCardinality.FIXED_ENUM, ValueSemantics.POINT_IN_TIME),
            new DynamicMapKey("policy.version", KeyCardinality.FIXED_ENUM, ValueSemantics.POINT_IN_TIME));

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

    public static final List<DynamicMapKey> EXPORT_EXPORTER_METRICS_CLASSIFICATION = List.of(
            new DynamicMapKey("export_batches", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("export_batch_failures", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("exported_spans", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("transport_dropped_spans", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("flush_failures", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("shutdown_failures", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("suppressed_spans_export_disabled", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("last_export_duration_nanos", KeyCardinality.FIXED_ENUM, ValueSemantics.POINT_IN_TIME),
            new DynamicMapKey("export_enabled", KeyCardinality.FIXED_ENUM, ValueSemantics.POINT_IN_TIME));

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

    /** Тот же bounded allowlist имён процессоров, что и на агентской стороне. */
    public static final List<String> PROCESSOR_ERRORS_BY_NAME_ALLOWLIST = List.of(
            "SpanWatchdogProcessor",
            "PlatformCompositeSpanProcessor",
            "MetricsSpanProcessor",
            "ScrubbingSpanProcessor",
            "ValidatingSpanProcessor",
            "PlatformDropOldestExportSpanProcessor");

    // -- Diagnostics ---------------------------------------------------------------

    public static final String DIAGNOSTICS_COMPOSITE_TYPE_NAME = "space.br1440.platform.tracing.DiagnosticsObservation";

    public static final String DIAGNOSTICS_ITEM_INVALID_CONFIG_COUNT = "invalidConfigCount";
    public static final String DIAGNOSTICS_ITEM_CONFIG_RELOAD_METRICS = "configReloadMetrics";
    public static final String DIAGNOSTICS_ITEM_CONFIG_AUDIT_TRAIL = "configAuditTrail";
    public static final String DIAGNOSTICS_ITEM_SAFE_WRAPPER_METRICS = "safeWrapperMetrics";
    public static final String DIAGNOSTICS_ITEM_PLATFORM_LOG_LEVEL = "platformLogLevel";
    public static final String DIAGNOSTICS_ITEM_PROPAGATION_ENABLED = "propagationEnabled";

    public static final List<DynamicMapKey> DIAGNOSTICS_CONFIG_RELOAD_CLASSIFICATION = List.of(
            new DynamicMapKey("updates.applied", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("updates.rejected", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("last_update.epoch_ms", KeyCardinality.FIXED_ENUM, ValueSemantics.POINT_IN_TIME));

    public static final List<DynamicMapKey> DIAGNOSTICS_SAFE_WRAPPER_CLASSIFICATION = List.of(
            new DynamicMapKey("sampler.failures", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("propagator.failures", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("resource.failures", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("scope.failures", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("safe_wrapper.suppressed_errors", KeyCardinality.FIXED_ENUM, ValueSemantics.CUMULATIVE),
            new DynamicMapKey("degraded_mode.enabled", KeyCardinality.FIXED_ENUM, ValueSemantics.POINT_IN_TIME),
            new DynamicMapKey("last_failure.epoch_ms", KeyCardinality.FIXED_ENUM, ValueSemantics.POINT_IN_TIME));

    public static final Map<String, String> COMPOSITE_TYPE_NAMES_BY_DOMAIN = Map.of(
            "SAMPLING", SAMPLING_COMPOSITE_TYPE_NAME,
            "SCRUBBING", SCRUBBING_COMPOSITE_TYPE_NAME,
            "VALIDATION", VALIDATION_COMPOSITE_TYPE_NAME,
            "EXPORT", EXPORT_COMPOSITE_TYPE_NAME,
            "PROCESSOR_METRICS", PROCESSOR_METRICS_COMPOSITE_TYPE_NAME,
            "DIAGNOSTICS", DIAGNOSTICS_COMPOSITE_TYPE_NAME);

}
