package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.time.Duration;
import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ExtensionDefaults {

    public static final double DEFAULT_SAMPLING_RATIO = 0.1d;
    public static final List<String> DEFAULT_FORCE_VALUES = List.of("on");
    public static final List<String> DEFAULT_DROP_PATHS = List.of(
            "/actuator/health", "/actuator/prometheus", "/actuator/info"
    );

    public static final String DEFAULT_SCRUBBING_MISSING_KEY_POLICY = "mask";
    public static final List<String> DEFAULT_BUILT_IN_RULES = BuiltInSpanAttributeScrubbingRules.defaultConfigNames();

    public static final boolean DEFAULT_RESOURCE_NORMALIZE_ENVIRONMENT = true;
    public static final boolean DEFAULT_RESOURCE_DETECT_CONTAINER_ID = false;

    public static final Duration DEFAULT_CLASSIFICATION_SLOW_THRESHOLD = Duration.ofSeconds(5);
    public static final Duration DEFAULT_CLASSIFICATION_NORMAL_THRESHOLD = Duration.ofSeconds(1);

    public static final boolean DEFAULT_SPAN_TIMEOUT_ENABLED = false;
    public static final Duration DEFAULT_SPAN_TIMEOUT_SPAN_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_SPAN_TIMEOUT_TRACE_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DEFAULT_SPAN_TIMEOUT_SCAN_INTERVAL = Duration.ofSeconds(5);
    public static final int DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_TRACES = 10_000;
    public static final int DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE = 1_024;
    public static final int DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS = 60_000;

    public static final String DEFAULT_QUEUE_OVERFLOW_POLICY = "DROP_OLDEST";
    public static final Duration DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    public static final boolean DEFAULT_BAGGAGE_PROPAGATION_ENABLED = true;
    public static final List<String> DEFAULT_BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS =
            List.of("traffic_source", "tenant_class", "platform.correlation.id");
    public static final List<String> DEFAULT_BAGGAGE_PROPAGATION_OUTBOUND_DENY_PATTERNS =
            List.of("password", "secret", "token");

    public static final boolean DEFAULT_BAGGAGE_PROCESSOR_ENABLED = true;
    public static final List<String> DEFAULT_BAGGAGE_PROCESSOR_ALLOWED_KEYS =
            List.of("traffic_source", "tenant_class", "platform.correlation.id");
    public static final List<String> DEFAULT_BAGGAGE_PROCESSOR_DENY_PATTERNS =
            List.of("password", "secret", "token");

    public static final boolean DEFAULT_ENABLED = true;

}
