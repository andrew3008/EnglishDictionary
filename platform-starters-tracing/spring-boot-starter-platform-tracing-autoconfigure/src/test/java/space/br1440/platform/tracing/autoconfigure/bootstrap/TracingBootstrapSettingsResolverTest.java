package space.br1440.platform.tracing.autoconfigure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class TracingBootstrapSettingsResolverTest {

    @Test
    void missingEnabledDefaultsToTrue() {
        assertThat(TracingBootstrapSettingsResolver.resolve(environment()).tracingEnabled()).isTrue();
    }

    @Test
    void enabledIsStrictBoolean() {
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
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(
                        environment().withProperty("platform.tracing.mode", " ")))
                .withMessageContaining("Obsolete Spring-only property 'platform.tracing.mode'");
    }

    @Test
    void obsoleteConfigurationEnabledFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment()
                        .withProperty("platform.tracing.configuration.enabled", "false")))
                .withMessageContaining("Obsolete Spring-only property 'platform.tracing.configuration.enabled'");
    }

    @Test
    void registrarRejectsSecondOwner() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        TracingBootstrapSettings settings = TracingBootstrapSettingsResolver.resolve(environment());

        TracingBootstrapSettingsRegistrar.register(beanFactory, settings);

        assertThat(beanFactory.getSingleton(TracingBootstrapPropertyNames.SETTINGS_BEAN_NAME))
                .isSameAs(settings);
        assertThatIllegalStateException()
                .isThrownBy(() -> TracingBootstrapSettingsRegistrar.register(beanFactory, settings))
                .withMessageContaining("already registered");
    }

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "HELM");
    }
}
