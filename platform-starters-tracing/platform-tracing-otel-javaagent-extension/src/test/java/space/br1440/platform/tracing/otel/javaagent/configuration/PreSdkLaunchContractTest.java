package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PreSdkLaunchContractTest {

    private static final String EXCEPTION_SIGNAL_ENV = "OTEL_SEMCONV_EXCEPTION_SIGNAL_PREVIEW";
    private static final String EXCEPTION_SIGNAL_FAILURE = "otel.semconv.exception.signal.preview is not supported "
            + "by the controlled agent profile: exception span events are required";
    private static final Function<String, String> ABSENT = name -> null;

    @ParameterizedTest
    @ValueSource(strings = {"otel.config.file", "otel.javaagent.configuration-file"})
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void resolvesSystemPropertiesBeforeSdkCreation(String propertyName) {
        // given
        String previous = System.getProperty(propertyName);
        System.setProperty(propertyName, "private-config-path");
        try {
            // when
            Throwable failure = catchThrowable(PreSdkLaunchContract::requireSupported);

            // then
            assertThat(failure).isInstanceOf(IllegalStateException.class)
                    .hasMessage(propertyName + " is not supported by the controlled agent profile");
        } finally {
            if (previous == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, previous);
            }
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void resolvesExceptionSignalSystemPropertyBeforeSdkCreation() {
        // given
        String previous = System.getProperty(PreSdkLaunchContract.EXCEPTION_SIGNAL_PREVIEW);
        System.setProperty(PreSdkLaunchContract.EXCEPTION_SIGNAL_PREVIEW, "logs");
        try {
            // when
            Throwable failure = catchThrowable(PreSdkLaunchContract::requireSupported);

            // then
            assertThat(failure).isInstanceOf(IllegalStateException.class)
                    .hasMessage(EXCEPTION_SIGNAL_FAILURE);
        } finally {
            if (previous == null) {
                System.clearProperty(PreSdkLaunchContract.EXCEPTION_SIGNAL_PREVIEW);
            } else {
                System.setProperty(PreSdkLaunchContract.EXCEPTION_SIGNAL_PREVIEW, previous);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"otel.config.file", "otel.javaagent.configuration-file"})
    void rejectsConfigurationChannelsBeforeSdkCreation(String propertyName) {
        // given
        Map<String, String> properties = Map.of(propertyName, "private-config-path");

        // when
        Throwable failure = catchThrowable(() -> PreSdkLaunchContract.requireSupported(properties::get, ABSENT, ABSENT));

        // then
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessage(propertyName + " is not supported by the controlled agent profile")
                .hasMessageNotContaining("private-config-path");
    }

    @Test
    void acceptsAbsentChannels() {
        // given
        Map<String, String> properties = Map.of();

        // when
        Throwable failure = catchThrowable(() -> PreSdkLaunchContract.requireSupported(properties::get, ABSENT, ABSENT));

        // then
        assertThat(failure).isNull();
    }

    @Test
    void acceptsEmptyChannels() {
        // given
        Map<String, String> properties = Map.of("otel.config.file", "", "otel.javaagent.configuration-file", "");

        // when
        Throwable failure = catchThrowable(() -> PreSdkLaunchContract.requireSupported(properties::get, ABSENT, ABSENT));

        // then
        assertThat(failure).isNull();
    }

    @Test
    void rejectsExceptionSignalAfterAcceptedConfigurationChannels() {
        // given
        Map<String, String> environment = Map.of(EXCEPTION_SIGNAL_ENV, "logs");

        // when
        Throwable failure = catchThrowable(() -> PreSdkLaunchContract.requireSupported(ABSENT, ABSENT, environment::get));

        // then
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessage(EXCEPTION_SIGNAL_FAILURE);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL,     NULL, true",
            "'',       NULL, true",
            "'',       logs, true",
            "NULL,     '',   true",
            "logs,     NULL, false",
            "logs/dup, NULL, false",
            "NULL,     logs, false",
            "spans,    NULL, false"
    })
    void resolvesExceptionSignalLikeAgentSystemProperty(String systemValue, String environmentValue, boolean accepted) {
        // given
        Map<String, String> systemProperties = new HashMap<>();
        Map<String, String> environment = new HashMap<>();
        if (systemValue != null) {
            systemProperties.put(PreSdkLaunchContract.EXCEPTION_SIGNAL_PREVIEW, systemValue);
        }
        if (environmentValue != null) {
            environment.put(EXCEPTION_SIGNAL_ENV, environmentValue);
        }

        // when
        Throwable failure = catchThrowable(() ->
                PreSdkLaunchContract.requireSpanEventExceptionSignal(systemProperties::get, environment::get));

        // then
        if (accepted) {
            assertThat(failure).isNull();
        } else {
            assertThat(failure).isInstanceOf(IllegalStateException.class)
                    .hasMessage(EXCEPTION_SIGNAL_FAILURE);
        }
    }
}
