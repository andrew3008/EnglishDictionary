package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.Locale;
import java.util.function.Function;

import space.br1440.platform.common.util.StringUtils;

import io.opentelemetry.javaagent.tooling.config.EarlyInitAgentConfig;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class PreSdkLaunchContract {

    static final String EXCEPTION_SIGNAL_PREVIEW = "otel.semconv.exception.signal.preview";
    private static final String EXCEPTION_SIGNAL_PREVIEW_ENV =
            EXCEPTION_SIGNAL_PREVIEW.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');

    public static void requireSupported() {
        requireSupported(EarlyInitAgentConfig.get()::getString, System::getProperty, System::getenv);
    }

    static void requireSupported(Function<String, String> agentProperties,
                                 Function<String, String> systemProperties,
                                 Function<String, String> environment) {
        requireAbsent(agentProperties, "otel.config.file");
        requireAbsent(agentProperties, "otel.javaagent.configuration-file");
        requireSpanEventExceptionSignal(systemProperties, environment);
    }

    static void requireSpanEventExceptionSignal(Function<String, String> systemProperties,
                                                Function<String, String> environment) {
        String value = systemProperties.apply(EXCEPTION_SIGNAL_PREVIEW);
        if (value == null) {
            value = environment.apply(EXCEPTION_SIGNAL_PREVIEW_ENV);
        }

        if (!StringUtils.isNullOrEmpty(value)) {
            throw new IllegalStateException(EXCEPTION_SIGNAL_PREVIEW
                    + " is not supported by the controlled agent profile: exception span events are required");
        }
    }

    private static void requireAbsent(Function<String, String> properties, String propertyName) {
        String value = properties.apply(propertyName);
        if (!StringUtils.isNullOrEmpty(value)) {
            throw new IllegalStateException(propertyName + " is not supported by the controlled agent profile");
        }
    }

}
