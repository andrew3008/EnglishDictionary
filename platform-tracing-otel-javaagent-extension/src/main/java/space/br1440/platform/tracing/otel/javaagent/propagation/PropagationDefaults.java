package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.experimental.UtilityClass;

import java.util.List;

import static space.br1440.platform.tracing.otel.javaagent.factory.FactoryUtils.orDefault;

@UtilityClass
public final class PropagationDefaults {

    public static final String PROP_BAGGAGE_ENABLED = "platform.tracing.javaagent.propagation.baggage.enabled";
    public static final String PROP_BAGGAGE_ALLOWED_KEYS = "platform.tracing.javaagent.propagation.baggage.allowed-keys";
    public static final String PROP_BAGGAGE_DENY_PATTERNS = "platform.tracing.javaagent.propagation.baggage.deny-patterns";

    public static final String PROP_HEADER_FORCE_TRACE = "platform.tracing.propagation.platform-headers.force-trace-header";
    public static final String PROP_HEADER_QA_TRACE = "platform.tracing.propagation.platform-headers.qa-trace-header";
    public static final String PROP_HEADER_REQUEST_ID = "platform.tracing.propagation.platform-headers.request-id-header";

    public static final boolean DEFAULT_BAGGAGE_ENABLED = true;
    public static final List<String> DEFAULT_BAGGAGE_ALLOWED_KEYS = List.of("traffic_source", "tenant_class", "platform.correlation.id");
    public static final List<String> DEFAULT_BAGGAGE_DENY_PATTERNS = List.of("password", "secret", "token");

    public static final String DEFAULT_FORCE_HEADER = "X-Trace-On";
    public static final String DEFAULT_QA_HEADER = "X-QA-Trace";
    public static final String DEFAULT_REQUEST_ID_HEADER = "X-Request-Id";

    public static String getForceTraceHeader(ConfigProperties config) {
        return orDefault(config.getString(PROP_HEADER_FORCE_TRACE), DEFAULT_FORCE_HEADER);
    }

    public static String getQaTraceHeader(ConfigProperties config) {
        return orDefault(config.getString(PROP_HEADER_QA_TRACE), DEFAULT_QA_HEADER);
    }

    public static String getRequestIdHeader(ConfigProperties config) {
        return orDefault(config.getString(PROP_HEADER_REQUEST_ID), DEFAULT_REQUEST_ID_HEADER);
    }

    public static boolean isBaggageEnabled(ConfigProperties config) {
        return config.getBoolean(PROP_BAGGAGE_ENABLED, DEFAULT_BAGGAGE_ENABLED);
    }

    public static List<String> getBaggageAllowedKeys(ConfigProperties config) {
        return orDefault(config.getList(PROP_BAGGAGE_ALLOWED_KEYS), DEFAULT_BAGGAGE_ALLOWED_KEYS);
    }

    public static List<String> getBaggageDenyPatterns(ConfigProperties config) {
        return orDefault(config.getList(PROP_BAGGAGE_DENY_PATTERNS), DEFAULT_BAGGAGE_DENY_PATTERNS);
    }
}
