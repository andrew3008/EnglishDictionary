package space.br1440.platform.tracing.autoconfigure.bootstrap;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracingBootstrapPropertyNames {

    public static final String ENABLED = "platform.tracing.enabled";
    public static final String CONTROL_CHANNEL = "platform.tracing.control.channel";
    public static final String KAFKA_PROPAGATE_PLATFORM_HEADERS = "platform.tracing.kafka.propagate-platform-headers";
    public static final String SUPPRESS_MICROMETER_HTTP_OBSERVATIONS = "platform.tracing.suppression.suppress-micrometer-http-observations";
    public static final String SETTINGS_BEAN_NAME = "tracingBootstrapSettings";

}
