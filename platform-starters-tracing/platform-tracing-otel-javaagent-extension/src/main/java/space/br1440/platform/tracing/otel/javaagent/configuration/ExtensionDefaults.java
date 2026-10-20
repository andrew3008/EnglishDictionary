package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ExtensionDefaults {

    public static final double DEFAULT_SAMPLING_RATIO = 0.1d;
    public static final Map<String, String> DEFAULT_SAMPLING_ROUTE_RATIOS = Map.of();
    public static final List<String> DEFAULT_FORCE_VALUES = List.of("on");
    public static final List<String> DEFAULT_DROP_PATHS = List.of("/actuator/health", "/actuator/prometheus", "/actuator/info");
    public static final boolean DEFAULT_EXPORTER_ENABLED = true;
    public static final boolean DEFAULT_TRACE_CONTROL_HEADERS_ENABLED = true;
    public static final AgentLogLevel DEFAULT_DIAGNOSTICS_LOG_LEVEL = AgentLogLevel.INFO;
    public static final SpanAttributeValidationLevel DEFAULT_SPAN_ATTRIBUTE_VALIDATION_LEVEL = SpanAttributeValidationLevel.WARNING;

    public static final String DEFAULT_SCRUBBING_MISSING_KEY_POLICY = "mask";
    public static final List<String> DEFAULT_BUILT_IN_RULES = BuiltInSpanAttributeScrubbingRules.defaultConfigNames();

    public static final Duration DEFAULT_CLASSIFICATION_SLOW_THRESHOLD = Duration.ofSeconds(5);
    public static final Duration DEFAULT_CLASSIFICATION_NORMAL_THRESHOLD = Duration.ofSeconds(1);

    public static final boolean DEFAULT_SPAN_TIMEOUT_ENABLED = false;
    public static final Duration DEFAULT_SPAN_TIMEOUT_MAX_SPAN_AGE = Duration.ofSeconds(30);
    public static final Duration DEFAULT_SPAN_TIMEOUT_MAX_TRACE_AGE = Duration.ofSeconds(60);
    public static final Duration DEFAULT_SPAN_TIMEOUT_SCAN_INTERVAL = Duration.ofSeconds(5);
    public static final int DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_TRACES = 10_000;
    public static final int DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE = 1_024;
    public static final int DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS = 60_000;

    public static final QueueOverflowPolicy DEFAULT_QUEUE_OVERFLOW_POLICY = QueueOverflowPolicy.DROP_OLDEST;
    public static final Duration DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    public static final boolean DEFAULT_BAGGAGE_PROPAGATION_ENABLED = true;
    public static final List<String> DEFAULT_BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS = List.of("traffic_source", "tenant_class", "platform.correlation.id");
    public static final List<String> DEFAULT_BAGGAGE_OUTBOUND_HEADER_DENY_PATTERNS = List.of("password", "secret", "token");
    public static final List<String> DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS = List.of("traffic_source", "tenant_class", "platform.correlation.id");
    public static final List<String> DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS = List.of("password", "secret", "token");

    public static final boolean DEFAULT_ENABLED = true;

}
