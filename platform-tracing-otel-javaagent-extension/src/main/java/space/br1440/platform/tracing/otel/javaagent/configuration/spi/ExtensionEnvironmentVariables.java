package space.br1440.platform.tracing.otel.javaagent.configuration.spi;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ExtensionEnvironmentVariables {

    static final String SERVICE_NAME = "PLATFORM_TRACING_SERVICE_NAME";
    static final String SERVICE_VERSION = "PLATFORM_TRACING_SERVICE_VERSION";
    static final String SERVICE_ENVIRONMENT = "PLATFORM_TRACING_SERVICE_ENVIRONMENT";
    static final String SERVICE_C_GROUP = "PLATFORM_TRACING_SERVICE_C_GROUP";
    static final String RESOURCE_POLICY_VERSION = "PLATFORM_TRACING_RESOURCE_POLICY_VERSION";

    static final String QUEUE_OVERFLOW_POLICY = "PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY";

    static final String OTEL_JAVAAGENT_EXTENSIONS = "OTEL_JAVAAGENT_EXTENSIONS";

}
