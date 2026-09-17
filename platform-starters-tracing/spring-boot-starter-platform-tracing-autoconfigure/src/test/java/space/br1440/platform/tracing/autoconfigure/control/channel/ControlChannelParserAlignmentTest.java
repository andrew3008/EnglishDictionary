package space.br1440.platform.tracing.autoconfigure.control.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;

class ControlChannelParserAlignmentTest {

    private static final String PROPERTY = "platform.tracing.control.channel";

    private final ApplicationContextRunner conditionRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ActuatorAdapterCondition.class,
                    ConfigServerAdapterCondition.class,
                    JmxWriteIntakeCondition.class);

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
                    boolean jmx = context.containsBean("jmxWriteIntake");
                    assertThat(actuator).isEqualTo(expected == TracingControlChannel.ACTUATOR);
                    assertThat(configServer).isEqualTo(expected == TracingControlChannel.CONFIG_SERVER);
                    assertThat(jmx).isEqualTo(expected != TracingControlChannel.STATIC);
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedWhitespace")
    void surroundingWhitespaceIsRejectedAsAcceptedInputChange(String description, String rawValue) {
        // given
        MockEnvironment environment = new MockEnvironment().withProperty(PROPERTY, rawValue);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Surrounding whitespace is not accepted");
    }

    static Stream<Arguments> acceptedValues() {
        return Stream.of(
                Arguments.of("canonical STATIC", "STATIC", TracingControlChannel.STATIC),
                Arguments.of("canonical CONFIG_SERVER", "CONFIG_SERVER", TracingControlChannel.CONFIG_SERVER),
                Arguments.of("canonical ACTUATOR", "ACTUATOR", TracingControlChannel.ACTUATOR),
                Arguments.of("mixed case static", "static", TracingControlChannel.STATIC),
                Arguments.of("mixed case Config_Server", "Config_Server", TracingControlChannel.CONFIG_SERVER),
                Arguments.of("mixed case actuator", "actuator", TracingControlChannel.ACTUATOR));
    }

    static Stream<Arguments> rejectedWhitespace() {
        return Stream.of(
                Arguments.of("leading space STATIC", " STATIC"),
                Arguments.of("trailing space STATIC", "STATIC "),
                Arguments.of("leading space CONFIG_SERVER", " CONFIG_SERVER"),
                Arguments.of("trailing space CONFIG_SERVER", "CONFIG_SERVER "),
                Arguments.of("leading space ACTUATOR", " ACTUATOR"),
                Arguments.of("trailing space ACTUATOR", "ACTUATOR "));
    }

    @Configuration
    @ConditionalOnProperty(name = PROPERTY, havingValue = "ACTUATOR")
    static class ActuatorAdapterCondition {

        @Bean
        String actuatorAdapter() {
            return "ACTUATOR";
        }
    }

    @Configuration
    @ConditionalOnProperty(name = PROPERTY, havingValue = "CONFIG_SERVER")
    static class ConfigServerAdapterCondition {

        @Bean
        String configServerAdapter() {
            return "CONFIG_SERVER";
        }
    }

    @Configuration
    @Conditional(JmxNestedCondition.class)
    static class JmxWriteIntakeCondition {

        @Bean
        String jmxWriteIntake() {
            return "JMX";
        }
    }

    static final class JmxNestedCondition extends AnyNestedCondition {

        JmxNestedCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = PROPERTY, havingValue = "ACTUATOR")
        @SuppressWarnings("unused")
        static class OnActuator {
        }

        @ConditionalOnProperty(name = PROPERTY, havingValue = "CONFIG_SERVER")
        @SuppressWarnings("unused")
        static class OnConfigServer {
        }
    }
}
