package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ExtensionPropertyNames {

    public static final String SAMPLING_RATIO = "platform.tracing.sampling.ratio";
    public static final String SAMPLING_ENABLED = "platform.tracing.sampling.enabled";
    public static final String SAMPLING_ROUTE_RATIOS = "platform.tracing.sampling.route-ratios";
    public static final String SAMPLING_FORCE_RECORD_VALUES = "platform.tracing.javaagent.sampling.force-record-values";
    public static final String SAMPLING_DROP_PATHS = "platform.tracing.javaagent.sampling.drop-paths";

    public static final String SCRUBBING_ENABLED = "platform.tracing.javaagent.scrubbing.enabled";
    public static final String SCRUBBING_BUILT_IN_RULES = "platform.tracing.javaagent.scrubbing.built-in-rules";
    public static final String SCRUBBING_HMAC_KEY = "platform.tracing.scrubbing.hmac-key";
    public static final String SCRUBBING_MISSING_KEY_POLICY = "platform.tracing.scrubbing.missing-key-policy";
    public static final String SCRUBBING_RULES_CONFIG = "platform.tracing.scrubbing.rules-config";
    public static final String SCRUBBING_RULES_EXTENSIONS = "platform.tracing.scrubbing.rules.extensions";
    public static final String SCRUBBING_RULES_VALIDATION_MODE = "platform.tracing.scrubbing.rules.validation-mode";

    public static final String METRICS_ENABLED = "platform.tracing.metrics.enabled";

    public static final String SPAN_ATTRIBUTES_VALIDATION_LEVEL =
            "platform.tracing.javaagent.span-attributes.validation.level";

    public static final String RESOURCE_NORMALIZE_ENVIRONMENT = "platform.tracing.resource.normalize-environment";
    public static final String RESOURCE_VALIDATION_MODE = "platform.tracing.resource.validation-mode";

    public static final String CLASSIFICATION_ENABLED = "platform.tracing.classification.enabled";
    public static final String CLASSIFICATION_SLOW_THRESHOLD = "platform.tracing.classification.slow-threshold";
    public static final String CLASSIFICATION_NORMAL_THRESHOLD = "platform.tracing.classification.normal-threshold";

    public static final String SPAN_TIMEOUT_ENABLED = "platform.tracing.span-timeout.enabled";
    public static final String SPAN_TIMEOUT_SPAN_TIMEOUT = "platform.tracing.span-timeout.span";
    public static final String SPAN_TIMEOUT_TRACE_TIMEOUT = "platform.tracing.span-timeout.trace";
    public static final String SPAN_TIMEOUT_SCAN_INTERVAL = "platform.tracing.span-timeout.scan-interval";
    public static final String SPAN_TIMEOUT_MAX_TRACKED_TRACES = "platform.tracing.span-timeout.max-tracked-traces";
    public static final String SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE = "platform.tracing.span-timeout.max-tracked-spans-per-trace";
    public static final String SPAN_TIMEOUT_MAX_TRACKED_SPANS = "platform.tracing.span-timeout.max-tracked-spans";

    public static final String QUEUE_OVERFLOW_POLICY = "platform.tracing.queue.overflow-policy";

    public static final String BAGGAGE_PROPAGATION_ENABLED = "platform.tracing.baggage.propagation.enabled";
    public static final String BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS = "platform.tracing.baggage.propagation.outbound-allowed-keys";
    public static final String BAGGAGE_PROPAGATION_OUTBOUND_DENY_PATTERNS = "platform.tracing.baggage.propagation.outbound-deny-patterns";

    public static final String BAGGAGE_PROCESSOR_ENABLED = "platform.tracing.baggage.processor.enabled";
    public static final String BAGGAGE_PROCESSOR_ALLOWED_KEYS = "platform.tracing.baggage.processor.allowed-keys";
    public static final String BAGGAGE_PROCESSOR_DENY_PATTERNS = "platform.tracing.baggage.processor.deny-patterns";

    public static final String OTEL_JAVAAGENT_EXTENSIONS = "otel.javaagent.extensions";
    public static final String CONTROLLED_AGENT_EXPECTED_SHA256 = "platform.tracing.controlled-agent.expected-sha256";

}
