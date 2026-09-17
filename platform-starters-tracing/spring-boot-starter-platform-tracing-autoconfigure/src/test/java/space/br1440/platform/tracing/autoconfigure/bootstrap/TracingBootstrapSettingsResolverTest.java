package space.br1440.platform.tracing.autoconfigure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

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
    void missingControlChannelFailsFast() {
        // given
        MockEnvironment environment = new MockEnvironment();

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Missing required property 'platform.tracing.control.channel'");
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
                .withMessageContaining("Missing required property 'platform.tracing.control.channel'");
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
                .withMessageContaining("STATIC")
                .withMessageContaining("CONFIG_SERVER")
                .withMessageContaining("ACTUATOR");
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
    void surroundingWhitespaceIsRejected() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", " ACTUATOR");

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .withMessageContaining("Surrounding whitespace is not accepted");
    }

    @Test
    void legalChannelsAreResolved() {
        // given
        // when
        // then
        assertThat(TracingBootstrapSettingsResolver.resolve(environment()).controlChannel())
                .isEqualTo(TracingControlChannel.STATIC);
        assertThat(TracingBootstrapSettingsResolver.resolve(environment()
                        .withProperty("platform.tracing.control.channel", "CONFIG_SERVER")).controlChannel())
                .isEqualTo(TracingControlChannel.CONFIG_SERVER);
        assertThat(TracingBootstrapSettingsResolver.resolve(environment()
                        .withProperty("platform.tracing.control.channel", "actuator")).controlChannel())
                .isEqualTo(TracingControlChannel.ACTUATOR);
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
                .hasMessage("Tracing disabled is incompatible with CONFIG_SERVER authority.");
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
                .withProperty("platform.tracing.control.channel", "STATIC");
    }
}
