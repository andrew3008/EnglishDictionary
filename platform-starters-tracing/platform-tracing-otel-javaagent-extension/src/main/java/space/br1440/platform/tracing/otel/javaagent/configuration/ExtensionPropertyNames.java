package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ExtensionPropertyNames {

    public static final String OTEL_JAVAAGENT_EXTENSIONS = "otel.javaagent.extensions";
    public static final String CONTROLLED_AGENT_EXPECTED_SHA256 = "platform.tracing.controlled-agent.expected-sha256";

    public static final String METRICS_ENABLED = "platform.tracing.metrics.enabled";

    public static final String RESOURCE_VALIDATION_MODE = "platform.tracing.resource.validation-mode";
    public static final String SERVICE_NAME = "platform.tracing.service.name";
    public static final String SERVICE_VERSION = "platform.tracing.service.version";
    public static final String SERVICE_ENVIRONMENT = "platform.tracing.service.environment";
    public static final String SERVICE_HOST = "platform.tracing.service.host";
    public static final String SERVICE_CONTAINER_ID = "platform.tracing.service.container-id";
    public static final String SPAN_ATTRIBUTES_VALIDATION_LEVEL = "platform.tracing.javaagent.span-attributes.validation.level";

    public static final String SAMPLING_ENABLED = "platform.tracing.sampling.enabled";
    public static final String SAMPLING_RATIO = "platform.tracing.sampling.ratio";
    public static final String SAMPLING_ROUTE_RATIOS = "platform.tracing.sampling.route-ratios";
    public static final String SAMPLING_FORCE_RECORD_VALUES = "platform.tracing.javaagent.sampling.force-record-values";
    public static final String SAMPLING_DROP_PATHS = "platform.tracing.javaagent.sampling.drop-paths";

    public static final String EXPORTER_ENABLED = "platform.tracing.exporter.enabled";
    public static final String TRACE_CONTROL_HEADERS_ENABLED = "platform.tracing.propagation.trace-control-headers.enabled";
    public static final String DIAGNOSTICS_LOG_LEVEL = "platform.tracing.diagnostics.log-level";

    public static final String CLASSIFICATION_ENABLED = "platform.tracing.classification.enabled";
    public static final String CLASSIFICATION_NORMAL_THRESHOLD = "platform.tracing.classification.normal-threshold";
    public static final String CLASSIFICATION_SLOW_THRESHOLD = "platform.tracing.classification.slow-threshold";

    public static final String SCRUBBING_ENABLED = "platform.tracing.javaagent.scrubbing.enabled";
    public static final String SCRUBBING_BUILT_IN_RULES = "platform.tracing.javaagent.scrubbing.built-in-rules";
    public static final String SCRUBBING_RULES_CONFIG = "platform.tracing.scrubbing.rules-config";
    public static final String SCRUBBING_RULES_EXTENSIONS = "platform.tracing.scrubbing.rules.extensions";
    public static final String SCRUBBING_RULES_VALIDATION_MODE = "platform.tracing.scrubbing.rules.validation-mode";
    public static final String SCRUBBING_HMAC_KEY = "platform.tracing.scrubbing.hmac-key";
    public static final String SCRUBBING_MISSING_KEY_POLICY = "platform.tracing.scrubbing.missing-key-policy";

    public static final String BAGGAGE_PROPAGATION_ENABLED = "platform.tracing.baggage.propagation.enabled";
    public static final String BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS = "platform.tracing.baggage.outbound-header.allowed-keys";
    public static final String BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS = "platform.tracing.baggage.outbound-header.deny-patterns";
    public static final String BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS = "platform.tracing.baggage.span-attributes.allowed-keys";
    public static final String BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS = "platform.tracing.baggage.span-attributes.deny-patterns";

    public static final String SPAN_TIMEOUT_ENABLED = "platform.tracing.span-timeout.enabled";
    public static final String SPAN_TIMEOUT_MAX_SPAN_AGE = "platform.tracing.span-timeout.max-span-age";
    public static final String SPAN_TIMEOUT_MAX_TRACE_AGE = "platform.tracing.span-timeout.max-trace-age";
    public static final String SPAN_TIMEOUT_SCAN_INTERVAL = "platform.tracing.span-timeout.scan-interval";
    public static final String SPAN_TIMEOUT_MAX_TRACKED_TRACES = "platform.tracing.span-timeout.max-tracked-traces";
    public static final String SPAN_TIMEOUT_MAX_TRACKED_SPANS = "platform.tracing.span-timeout.max-tracked-spans";
    public static final String SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE = "platform.tracing.span-timeout.max-tracked-spans-per-trace";

}
