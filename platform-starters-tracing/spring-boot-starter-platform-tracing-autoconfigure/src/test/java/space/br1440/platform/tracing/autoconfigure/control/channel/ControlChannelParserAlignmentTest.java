package space.br1440.platform.tracing.autoconfigure.control.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannelCondition;

class ControlChannelParserAlignmentTest {

    private static final String PROPERTY = "platform.tracing.control.channel";

    private final ApplicationContextRunner conditionRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ActuatorAdapterCondition.class,
                    ConfigServerAdapterCondition.class,
                    NoneReaderCondition.class,
                    WritableJmxClientCondition.class);

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedValues")
    void acceptedSelectorMatchesProductionConditions(String description, String rawValue, TracingControlChannel expected) {
        // given
        MockEnvironment environment = new MockEnvironment().withProperty(PROPERTY, rawValue);

        // when
        TracingBootstrapSettings settings = TracingBootstrapSettingsResolver.resolve(environment);

        // then
        assertThat(settings.controlChannel()).isEqualTo(expected);
        conditionRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("raw-selector", Map.of(PROPERTY, rawValue))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty(PROPERTY)).isEqualTo(rawValue);
                    boolean actuator = context.containsBean("actuatorAdapter");
                    boolean configServer = context.containsBean("configServerAdapter");
                    boolean noneReader = context.containsBean("noneReader");
                    boolean writableJmx = context.containsBean("writableJmxClient");
                    assertThat(actuator).isEqualTo(expected == TracingControlChannel.ACTUATOR);
                    assertThat(configServer).isEqualTo(expected == TracingControlChannel.CONFIG_SERVER);
                    assertThat(noneReader).isEqualTo(expected == TracingControlChannel.NONE);
                    assertThat(writableJmx).isEqualTo(expected != TracingControlChannel.NONE);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"REMOTE", ""})
    void invalidChannelMatchesNoConditionAndResolveFails(String rawValue) {
        // given
        MockEnvironment environment = new MockEnvironment().withProperty(PROPERTY, rawValue);

        // when
        // then
        conditionRunner.withPropertyValues(PROPERTY + "=" + rawValue)
                .run(context -> assertThat(context.getBeansOfType(String.class)).isEmpty());
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid"})
    void invalidEnabledMatchesNoConditionAndResolveFails(String rawValue) {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.enabled", rawValue)
                .withProperty(PROPERTY, "NONE");

        // when
        // then
        conditionRunner.withPropertyValues(
                        "platform.tracing.enabled=" + rawValue,
                        PROPERTY + "=NONE")
                .run(context -> assertThat(context.getBeansOfType(String.class)).isEmpty());
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Accepted values: true, false");
    }

    static Stream<Arguments> acceptedValues() {
        return Stream.of(
                Arguments.of("canonical NONE", "NONE", TracingControlChannel.NONE),
                Arguments.of("canonical CONFIG_SERVER", "CONFIG_SERVER", TracingControlChannel.CONFIG_SERVER),
                Arguments.of("canonical ACTUATOR", "ACTUATOR", TracingControlChannel.ACTUATOR),
                Arguments.of("mixed case none", "none", TracingControlChannel.NONE),
                Arguments.of("mixed case Config_Server", "Config_Server", TracingControlChannel.CONFIG_SERVER),
                Arguments.of("mixed case actuator", "actuator", TracingControlChannel.ACTUATOR),
                Arguments.of("surrounding whitespace", " ACTUATOR ", TracingControlChannel.ACTUATOR));
    }

    @Configuration
    @Conditional(TracingControlChannelCondition.OnActuator.class)
    static class ActuatorAdapterCondition {

        @Bean
        String actuatorAdapter() {
            return "ACTUATOR";
        }
    }

    @Configuration
    @Conditional(TracingControlChannelCondition.OnConfigServer.class)
    static class ConfigServerAdapterCondition {

        @Bean
        String configServerAdapter() {
            return "CONFIG_SERVER";
        }
    }

    @Configuration
    @Conditional(TracingControlChannelCondition.OnNone.class)
    static class NoneReaderCondition {

        @Bean
        String noneReader() {
            return "NONE";
        }
    }

    @Configuration
    @Conditional(TracingControlChannelCondition.OnWritable.class)
    static class WritableJmxClientCondition {

        @Bean
        String writableJmxClient() {
            return "WRITABLE";
        }
    }
}
