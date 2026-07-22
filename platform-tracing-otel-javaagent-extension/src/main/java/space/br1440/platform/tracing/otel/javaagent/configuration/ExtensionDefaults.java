package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.BuiltInSpanAttributeScrubbingRules;

import java.time.Duration;
import java.util.List;

@UtilityClass
public final class ExtensionDefaults {

    public static final double DEFAULT_SAMPLING_RATIO = 0.1d;
    public static final String DEFAULT_FORCE_HEADER = PlatformHeaders.X_TRACE_ON;
    public static final String DEFAULT_QA_HEADER = PlatformHeaders.X_QA_TRACE;
    public static final List<String> DEFAULT_FORCE_VALUES = List.of("on");
    public static final List<String> DEFAULT_DROP_PATHS = List.of(
            "/actuator/health", "/actuator/prometheus", "/actuator/info"
    );

    public static final List<String> DEFAULT_REMOTE_SERVICE_PRIORITY = List.of(
            "peer.service", "rpc.service", "server.address"
    );

    public static final String DEFAULT_SCRUBBING_MISSING_KEY_POLICY = "mask";
    public static final String DEFAULT_SCRUBBING_VALIDATION_MODE = "LENIENT";
    public static final List<String> DEFAULT_BUILT_IN_RULES = BuiltInSpanAttributeScrubbingRules.defaultConfigNames();

    public static final boolean DEFAULT_VALIDATION_STRICT = false;
    public static final boolean DEFAULT_VALIDATION_STRICT_RUNTIME_ALLOWED = false;
    /** SP-02: Default validation mode when neither validation.mode nor legacy strict is set. */
    public static final String DEFAULT_VALIDATION_MODE = "LENIENT";

    public static final String DEFAULT_RESOURCE_POLICY_VERSION = "2026.06.08";
    public static final boolean DEFAULT_RESOURCE_NORMALIZE_ENVIRONMENT = true;
    public static final String DEFAULT_RESOURCE_VALIDATION_MODE = "LENIENT";
    public static final boolean DEFAULT_RESOURCE_DETECT_CONTAINER_ID = false;

    public static final Duration DEFAULT_CLASSIFICATION_SLOW_THRESHOLD = Duration.ofSeconds(5);
    public static final Duration DEFAULT_CLASSIFICATION_NORMAL_THRESHOLD = Duration.ofSeconds(1);

    // SP-01: Watchdog disabled by default — must be explicitly opted in.
    public static final boolean DEFAULT_WATCHDOG_ENABLED = false;
    public static final Duration DEFAULT_WATCHDOG_SPAN_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_WATCHDOG_TRACE_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DEFAULT_WATCHDOG_SCAN_INTERVAL = Duration.ofSeconds(5);

    public static final String DEFAULT_QUEUE_OVERFLOW_POLICY = "DROP_OLDEST";
    public static final Duration DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    public static final boolean DEFAULT_BAGGAGE_ENABLED = true;
    public static final List<String> DEFAULT_BAGGAGE_ALLOWLIST =
            List.of("traffic_source", "tenant_class", "platform.correlation.id");
    public static final List<String> DEFAULT_BAGGAGE_DENY_PATTERNS = List.of("password", "secret", "token");

    public static final boolean DEFAULT_ENABLED = true;

}
