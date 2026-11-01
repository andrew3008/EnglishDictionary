package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreSdkLaunchContractTest {

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

    @ParameterizedTest
    @ValueSource(strings = {"otel.config.file", "otel.javaagent.configuration-file"})
    void rejectsConfigurationChannelsBeforeSdkCreation(String propertyName) {
        // given
        Map<String, String> properties = Map.of(propertyName, "private-config-path");

        // when
        Throwable failure = catchThrowable(() -> PreSdkLaunchContract.requireSupported(properties::get));

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
        Throwable failure = catchThrowable(() -> PreSdkLaunchContract.requireSupported(properties::get));

        // then
        assertThat(failure).isNull();
    }

    @Test
    void acceptsEmptyChannels() {
        // given
        Map<String, String> properties = Map.of("otel.config.file", "", "otel.javaagent.configuration-file", "");

        // when
        Throwable failure = catchThrowable(() -> PreSdkLaunchContract.requireSupported(properties::get));

        // then
        assertThat(failure).isNull();
    }
}
