package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.experimental.UtilityClass;

import static space.br1440.platform.tracing.otel.javaagent.factory.FactoryUtils.orDefault;

/**
 * Header-name defaults only. Baggage configuration (enabled/allowed-keys/deny-patterns) is owned
 * exclusively by {@code BaggageExtensionConfig} — see its Javadoc for the propagation/processor
 * plane split. This class must not reintroduce a second baggage reader.
 */
@UtilityClass
public final class PropagationDefaults {

    public static final String PROP_HEADER_FORCE_TRACE = "platform.tracing.propagation.platform-headers.force-trace-header";
    public static final String PROP_HEADER_QA_TRACE = "platform.tracing.propagation.platform-headers.qa-trace-header";
    public static final String PROP_HEADER_REQUEST_ID = "platform.tracing.propagation.platform-headers.request-id-header";

    static final String DEFAULT_FORCE_HEADER = "X-Trace-On";
    static final String DEFAULT_QA_HEADER = "X-QA-Trace";
    static final String DEFAULT_REQUEST_ID_HEADER = "X-Request-Id";

    static String getForceTraceHeader(ConfigProperties config) {
        return orDefault(config.getString(PROP_HEADER_FORCE_TRACE), DEFAULT_FORCE_HEADER);
    }

    static String getQaTraceHeader(ConfigProperties config) {
        return orDefault(config.getString(PROP_HEADER_QA_TRACE), DEFAULT_QA_HEADER);
    }

    static String getRequestIdHeader(ConfigProperties config) {
        return orDefault(config.getString(PROP_HEADER_REQUEST_ID), DEFAULT_REQUEST_ID_HEADER);
    }
}
