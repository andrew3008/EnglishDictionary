package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ExtensionEnvironmentVariables {

    static final String QUEUE_OVERFLOW_POLICY = "PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY";
    static final String OTEL_JAVAAGENT_EXTENSIONS = "OTEL_JAVAAGENT_EXTENSIONS";

}
