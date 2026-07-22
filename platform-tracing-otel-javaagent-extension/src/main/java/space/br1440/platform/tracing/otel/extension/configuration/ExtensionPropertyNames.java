package space.br1440.platform.tracing.otel.extension.configuration;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ExtensionPropertyNames {

    public static final String SAMPLING_RATIO = "platform.tracing.sampling.ratio";
    public static final String SAMPLING_ENABLED = "platform.tracing.sampling.enabled";
    public static final String SAMPLING_ROUTE_RATIOS = "platform.tracing.sampling.route-ratios";
    public static final String SAMPLING_FORCE_RECORD_HEADER = "platform.tracing.sampling.force-record-header";
    public static final String SAMPLING_FORCE_RECORD_VALUES = "platform.tracing.sampling.force-record-values";
    public static final String SAMPLING_QA_HEADER = "platform.tracing.sampling.qa-header";
    public static final String SAMPLING_DROP_PATHS = "platform.tracing.sampling.drop-paths";

    public static final String ENRICHING_ENABLED = "platform.tracing.enriching.enabled";
    public static final String ENRICHING_REMOTE_SERVICE_PRIORITY = "platform.tracing.enriching.remote-service-priority";

    public static final String SCRUBBING_ENABLED = "platform.tracing.scrubbing.enabled";
    public static final String SCRUBBING_BUILT_IN_RULES = "platform.tracing.scrubbing.built-in-rules";
    public static final String SCRUBBING_HMAC_KEY = "platform.tracing.scrubbing.hmac-key";
    public static final String SCRUBBING_MISSING_KEY_POLICY = "platform.tracing.scrubbing.missing-key-policy";
    public static final String SCRUBBING_HASH_KEY_ID = "platform.tracing.scrubbing.hash.key-id";
    public static final String SCRUBBING_RULES_CONFIG = "platform.tracing.scrubbing.rules-config";
    public static final String SCRUBBING_RULES_EXTENSIONS = "platform.tracing.scrubbing.rules.extensions";
    public static final String SCRUBBING_RULES_VALIDATION_MODE = "platform.tracing.scrubbing.rules.validation-mode";

    public static final String METRICS_ENABLED = "platform.tracing.metrics.enabled";

    public static final String VALIDATION_ENABLED = "platform.tracing.validation.enabled";
    public static final String VALIDATION_STRICT = "platform.tracing.validation.strict";
    public static final String VALIDATION_STRICT_RUNTIME_ALLOWED = "platform.tracing.validation.strict-runtime-allowed";
    /** SP-02: New primary property for validation mode (LENIENT|STRICT|DISABLED). */
    public static final String VALIDATION_MODE = "platform.tracing.validation.mode";
    /** SP-02: Control-plane environment signal (production|staging|dev). NOT deployment.environment.name. */
    public static final String PLATFORM_ENVIRONMENT = "platform.environment";

    public static final String RESOURCE_POLICY_VERSION = "platform.tracing.resource.policy-version";
    public static final String RESOURCE_NORMALIZE_ENVIRONMENT = "platform.tracing.resource.normalize-environment";
    public static final String RESOURCE_VALIDATION_MODE = "platform.tracing.resource.validation-mode";
    public static final String RESOURCE_DETECT_CONTAINER_ID = "platform.tracing.resource.detect-container-id";

    public static final String RESOURCE_POLICY_VERSION_ATTR = "platform.tracing.policy.version";

    public static final String CLASSIFICATION_ENABLED = "platform.tracing.classification.enabled";
    public static final String CLASSIFICATION_SLOW_THRESHOLD = "platform.tracing.classification.slow-threshold";
    public static final String CLASSIFICATION_NORMAL_THRESHOLD = "platform.tracing.classification.normal-threshold";

    public static final String WATCHDOG_ENABLED = "platform.tracing.watchdog.enabled";
    public static final String WATCHDOG_SPAN_TIMEOUT = "platform.tracing.watchdog.span-timeout";
    public static final String WATCHDOG_TRACE_TIMEOUT = "platform.tracing.watchdog.trace-timeout";
    public static final String WATCHDOG_SCAN_INTERVAL = "platform.tracing.watchdog.scan-interval";

    public static final String QUEUE_OVERFLOW_POLICY = "platform.tracing.queue.overflow-policy";

    public static final String BAGGAGE_ENABLED = "platform.tracing.baggage.enabled";
    public static final String BAGGAGE_ALLOWLIST_KEYS = "platform.tracing.baggage.allowlist-keys";
    public static final String BAGGAGE_DENY_PATTERNS = "platform.tracing.baggage.deny-patterns";

    public static final String SDK_MODE = "platform.tracing.sdk.mode";

    public static final String CONTROL_RUNTIME_MUTATION_ENABLED =
            "platform.tracing.control.runtime-mutation.enabled";

    public static final String OTEL_JAVAAGENT_EXTENSIONS = "otel.javaagent.extensions";

}
