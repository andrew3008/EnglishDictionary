package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.util.List;

import static space.br1440.platform.tracing.otel.javaagent.factory.FactoryUtils.orDefault;

public final class PropagationDefaults {

    public static final String PROP_W3C_DIAGNOSTICS_ONLY = "platform.tracing.propagation.w3c.diagnostics-only";

    public static final String PROP_BAGGAGE_ENABLED = "platform.tracing.propagation.baggage.enabled";
    public static final String PROP_BAGGAGE_ALLOWED_KEYS = "platform.tracing.propagation.baggage.allowed-keys";
    public static final String PROP_BAGGAGE_DENY_PATTERNS = "platform.tracing.propagation.baggage.deny-patterns";

    public static final String PROP_HEADER_FORCE_TRACE = "platform.tracing.propagation.platform-headers.force-trace-header";
    public static final String PROP_HEADER_QA_TRACE = "platform.tracing.propagation.platform-headers.qa-trace-header";
    public static final String PROP_HEADER_REQUEST_ID = "platform.tracing.propagation.platform-headers.request-id-header";

    // Главный переключатель исходящей передачи платформенных заголовков (имя выровнено со Spring-стороной).
    public static final String PROP_OUTBOUND_ENABLED = "platform.tracing.propagation.outbound.enabled";
    public static final String PROP_OUTBOUND_TRUSTED_HOSTS = "platform.tracing.propagation.outbound.trusted-host-patterns";
    public static final String PROP_OUTBOUND_PROPAGATE_FORCE = "platform.tracing.propagation.outbound.propagate-force-trace";
    public static final String PROP_OUTBOUND_PROPAGATE_QA = "platform.tracing.propagation.outbound.propagate-qa-trace";
    public static final String PROP_OUTBOUND_PROPAGATE_REQUEST_ID = "platform.tracing.propagation.outbound.propagate-request-id";

    public static final boolean DEFAULT_OUTBOUND_ENABLED = false;
    public static final boolean DEFAULT_OUTBOUND_PROPAGATE_FORCE = false;
    public static final boolean DEFAULT_OUTBOUND_PROPAGATE_QA = false;
    public static final boolean DEFAULT_OUTBOUND_PROPAGATE_REQUEST_ID = true;

    public static final boolean DEFAULT_BAGGAGE_ENABLED = true;
    public static final List<String> DEFAULT_BAGGAGE_ALLOWED_KEYS =
            List.of("traffic_source", "tenant_class", "platform.correlation.id");
    public static final List<String> DEFAULT_BAGGAGE_DENY_PATTERNS = List.of("password", "secret", "token");

    public static final String DEFAULT_FORCE_HEADER = "X-Trace-On";
    public static final String DEFAULT_QA_HEADER = "X-QA-Trace";
    public static final String DEFAULT_REQUEST_ID_HEADER = "X-Request-Id";

    private PropagationDefaults() {
    }

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

    // Outbound: agent-side зеркало конфигурации (источник истины — Spring TracingProperties.Propagation.Outbound).
    public static boolean isOutboundEnabled(ConfigProperties config) {
        return config.getBoolean(PROP_OUTBOUND_ENABLED, DEFAULT_OUTBOUND_ENABLED);
    }

    public static List<String> getTrustedHostPatterns(ConfigProperties config) {
        return orDefault(config.getList(PROP_OUTBOUND_TRUSTED_HOSTS), List.of());
    }

    public static boolean isPropagateForceTrace(ConfigProperties config) {
        return config.getBoolean(PROP_OUTBOUND_PROPAGATE_FORCE, DEFAULT_OUTBOUND_PROPAGATE_FORCE);
    }

    public static boolean isPropagateQaTrace(ConfigProperties config) {
        return config.getBoolean(PROP_OUTBOUND_PROPAGATE_QA, DEFAULT_OUTBOUND_PROPAGATE_QA);
    }

    public static boolean isPropagateRequestId(ConfigProperties config) {
        return config.getBoolean(PROP_OUTBOUND_PROPAGATE_REQUEST_ID, DEFAULT_OUTBOUND_PROPAGATE_REQUEST_ID);
    }
}
