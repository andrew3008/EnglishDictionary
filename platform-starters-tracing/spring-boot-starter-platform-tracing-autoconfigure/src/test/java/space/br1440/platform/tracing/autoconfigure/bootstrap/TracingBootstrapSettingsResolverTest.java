package space.br1440.platform.tracing.autoconfigure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

@ExtendWith(OutputCaptureExtension.class)
class TracingBootstrapSettingsResolverTest {

    @Test
    void missingEnabledDefaultsToTrue() {
        // given
        // when
        // then
        assertThat(TracingBootstrapSettingsResolver.resolve(environment()).tracingEnabled()).isTrue();
    }

    @Test
    void enabledIsStrictBoolean() {
        // given
        // when
        // then
        assertThat(TracingBootstrapSettingsResolver.resolve(
                        environment().withProperty("platform.tracing.enabled", "true")).tracingEnabled())
                .isTrue();
        assertThat(TracingBootstrapSettingsResolver.resolve(
                        environment().withProperty("platform.tracing.enabled", "FALSE")).tracingEnabled())
                .isFalse();
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(
                        environment().withProperty("platform.tracing.enabled", "yes")))
                .withMessageContaining("Accepted values: true, false");
    }

    @Test
    void obsoleteModeFailsFast() {
        // given
        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(
                        environment().withProperty("platform.tracing.mode", " ")))
                .withMessageContaining("Obsolete Spring-only property 'platform.tracing.mode'");
    }

    @Test
    void obsoleteConfigurationEnabledFailsFast() {
        // given
        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment()
                        .withProperty("platform.tracing.configuration.enabled", "false")))
                .withMessageContaining("Obsolete Spring-only property 'platform.tracing.configuration.enabled'");
    }

    @Test
    void enabledTracingWithoutControlChannelFailsFast() {
        // given
        MockEnvironment environment = new MockEnvironment();

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Missing required property 'platform.tracing.control.channel'");
    }

    @Test
    void disabledTracingWithoutControlChannelUsesNone() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.enabled", "false");

        // when
        TracingBootstrapSettings settings = TracingBootstrapSettingsResolver.resolve(environment);

        // then
        assertThat(settings.tracingEnabled()).isFalse();
        assertThat(settings.controlChannel()).isEqualTo(TracingControlChannel.NONE);
    }

    @Test
    void emptyControlChannelFailsFast() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "  ");

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Property 'platform.tracing.control.channel' must not be blank");
    }

    @Test
    void unknownControlChannelFailsFast() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "REMOTE");

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Invalid property 'platform.tracing.control.channel'")
                .withMessageContaining("NONE")
                .withMessageContaining("CONFIG_SERVER")
                .withMessageContaining("ACTUATOR");
    }

    @Test
    void staticControlChannelIsRejectedWithoutAlias() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "STATIC");

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Invalid property 'platform.tracing.control.channel'='STATIC'");
    }

    @Test
    void helmIsNotMappedToStatic() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "HELM");

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Invalid property 'platform.tracing.control.channel'='HELM'");
    }

    @Test
    void obsoleteAuthorityNamesReplacement() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "HELM");

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Obsolete Spring-only property 'platform.tracing.configuration.authority'")
                .withMessageContaining("platform.tracing.control.channel");
    }

    @Test
    void dualKeysFailWithoutPrecedence() {
        // given
        MockEnvironment environment = environment()
                .withProperty("platform.tracing.configuration.authority", "HELM");

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Obsolete Spring-only property 'platform.tracing.configuration.authority'")
                .withMessageContaining("platform.tracing.control.channel");
    }

    @Test
    void obsoleteAuthorityEnvIsRejectedThroughSystemEnvironmentPropertySource() {
        // given
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment",
                Map.of("PLATFORM_TRACING_CONFIGURATION_AUTHORITY", "HELM")));

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Obsolete Spring-only property 'platform.tracing.configuration.authority'")
                .withMessageContaining("platform.tracing.control.channel");
    }

    @Test
    void surroundingWhitespaceIsAccepted() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", " ACTUATOR");

        // when
        TracingBootstrapSettings settings = TracingBootstrapSettingsResolver.resolve(environment);

        // then
        assertThat(settings.controlChannel()).isEqualTo(TracingControlChannel.ACTUATOR);
    }

    @ParameterizedTest
    @MethodSource("enabledParsingCases")
    void parseEnabledReturnsValueWithoutThrowing(String rawValue, Boolean expected) {
        // given
        MockEnvironment environment = new MockEnvironment();
        if (rawValue != null) {
            environment.withProperty("platform.tracing.enabled", rawValue);
        }

        // when
        Optional<Boolean> parsed = TracingBootstrapSettingsResolver.parseEnabled(environment);

        // then
        assertThat(parsed).isEqualTo(Optional.ofNullable(expected));
    }

    @ParameterizedTest
    @MethodSource("controlChannelParsingCases")
    void parseControlChannelReturnsValueWithoutThrowing(String rawChannel,
                                                        String rawEnabled,
                                                        TracingControlChannel expected) {
        // given
        MockEnvironment environment = new MockEnvironment();
        if (rawChannel != null) {
            environment.withProperty("platform.tracing.control.channel", rawChannel);
        }
        if (rawEnabled != null) {
            environment.withProperty("platform.tracing.enabled", rawEnabled);
        }

        // when
        Optional<TracingControlChannel> parsed =
                TracingBootstrapSettingsResolver.parseControlChannel(environment);

        // then
        assertThat(parsed).isEqualTo(Optional.ofNullable(expected));
    }

    @Test
    void legalChannelsAreResolved() {
        // given
        // when
        // then
        assertThat(TracingBootstrapSettingsResolver.resolve(environment()).controlChannel())
                .isEqualTo(TracingControlChannel.NONE);
        assertThat(TracingBootstrapSettingsResolver.resolve(environment()
                        .withProperty("platform.tracing.control.channel", "CONFIG_SERVER")).controlChannel())
                .isEqualTo(TracingControlChannel.CONFIG_SERVER);
        assertThat(TracingBootstrapSettingsResolver.resolve(environment()
                        .withProperty("platform.tracing.control.channel", "actuator")).controlChannel())
                .isEqualTo(TracingControlChannel.ACTUATOR);
    }

    @Test
    void environmentBootstrapKeysDoNotWarnAndHyphenatedNamesResolve(CapturedOutput output) {
        // given
        MockEnvironment environment = environment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment",
                Map.of(
                        "PLATFORM_TRACING_SAMPLING_ROUTE_RATIOS", "/orders=0.5",
                        "PLATFORM_TRACING_JAVAAGENT_SAMPLING_DROP_PATHS", "/health")));

        // when
        TracingBootstrapSettingsResolver.resolve(environment);

        // then
        assertThat(environment.getProperty("platform.tracing.sampling.route-ratios"))
                .isEqualTo("/orders=0.5");
        assertThat(environment.getProperty("platform.tracing.javaagent.sampling.drop-paths"))
                .isEqualTo("/health");
        assertThat(output).doesNotContain("Java-agent bootstrap-only properties");
    }

    @Test
    void systemPropertyBootstrapKeyDoesNotWarn(CapturedOutput output) {
        // given
        MockEnvironment environment = environment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "systemProperties",
                Map.of("platform.tracing.sampling.ratio", "0.25")));

        // when
        TracingBootstrapSettingsResolver.resolve(environment);

        // then
        assertThat(output).doesNotContain("Java-agent bootstrap-only properties");
    }

    @Test
    void applicationConfigBootstrapKeyWarns(CapturedOutput output) {
        // given
        MockEnvironment environment = environment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "Config resource 'class path resource [application.yaml]' via location 'classpath:/'",
                Map.of("platform.tracing.sampling.ratio", "0.25")));

        // when
        TracingBootstrapSettingsResolver.resolve(environment);

        // then
        assertThat(output).contains("platform.tracing.sampling.ratio");
    }

    @Test
    void configServerBootstrapKeyWarns(CapturedOutput output) {
        // given
        MockEnvironment environment = environment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "configserver:file:/config/application.yaml",
                Map.of("platform.tracing.exporter.enabled", "false")));

        // when
        TracingBootstrapSettingsResolver.resolve(environment);

        // then
        assertThat(output).contains("platform.tracing.exporter.enabled");
    }

    @Test
    void winningEnvironmentValueSuppressesLowerConfigDataWarning(CapturedOutput output) {
        // given
        MockEnvironment environment = environment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "Config resource 'class path resource [application.yaml]' via location 'classpath:/'",
                Map.of("platform.tracing.sampling.ratio", "0.25")));
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment",
                Map.of("PLATFORM_TRACING_SAMPLING_RATIO", "0.75")));

        // when
        TracingBootstrapSettingsResolver.resolve(environment);

        // then
        assertThat(output).doesNotContain("Java-agent bootstrap-only properties");
    }

    @Test
    void disabledWriteChannelFailsBeforeSettingsAreReturned() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "CONFIG_SERVER")
                .withProperty("platform.tracing.enabled", "false");

        // when
        // then
        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessage("Tracing disabled is incompatible with CONFIG_SERVER control channel.");
    }

    @Test
    void registrarRejectsSecondOwner() {
        // given
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        TracingBootstrapSettings settings = TracingBootstrapSettingsResolver.resolve(environment());

        // when
        TracingBootstrapSettingsRegistrar.register(beanFactory, settings);

        // then
        assertThat(beanFactory.getSingleton(TracingBootstrapPropertyNames.SETTINGS_BEAN_NAME))
                .isSameAs(settings);
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsRegistrar.register(beanFactory, settings))
                .withMessageContaining("already registered");
    }

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "NONE");
    }

    static Stream<Arguments> enabledParsingCases() {
        return Stream.of(
                Arguments.of(null, true),
                Arguments.of(" true ", true),
                Arguments.of("FALSE", false),
                Arguments.of("", null),
                Arguments.of("yes", null)
        );
    }

    static Stream<Arguments> controlChannelParsingCases() {
        return Stream.of(
                Arguments.of(null, null, null),
                Arguments.of(null, "false", TracingControlChannel.NONE),
                Arguments.of(" NONE ", null, TracingControlChannel.NONE),
                Arguments.of("config_server", null, TracingControlChannel.CONFIG_SERVER),
                Arguments.of("Actuator", null, TracingControlChannel.ACTUATOR),
                Arguments.of("", null, null),
                Arguments.of("REMOTE", null, null),
                Arguments.of(null, "", null)
        );
    }
}
