package space.br1440.platform.tracing.autoconfigure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class TracingBootstrapSettingsResolverTest {

    private final TracingBootstrapSettingsResolver resolver = new TracingBootstrapSettingsResolver();

    @Test
    void missingEnabledDefaultsToTrue() {
        assertThat(resolver.resolve(environment()).tracingEnabled()).isTrue();
    }

    @Test
    void enabledIsStrictBoolean() {
        assertThat(resolver.resolve(environment().withProperty("platform.tracing.enabled", "true")).tracingEnabled())
                .isTrue();
        assertThat(resolver.resolve(environment().withProperty("platform.tracing.enabled", "FALSE")).tracingEnabled())
                .isFalse();
        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(environment().withProperty("platform.tracing.enabled", "yes")))
                .withMessageContaining("Accepted values: true, false");
    }

    @Test
    void obsoleteModeFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(environment().withProperty("platform.tracing.mode", " ")))
                .withMessageContaining("Obsolete Spring-only property 'platform.tracing.mode'");
    }

    @Test
    void obsoleteConfigurationEnabledFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(environment()
                        .withProperty("platform.tracing.configuration.enabled", "false")))
                .withMessageContaining("Obsolete Spring-only property 'platform.tracing.configuration.enabled'");
    }

    @Test
    void registrarRejectsSecondOwner() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        TracingBootstrapSettings settings = resolver.resolve(environment());
        TracingBootstrapSettingsRegistrar registrar = new TracingBootstrapSettingsRegistrar();

        registrar.register(beanFactory, settings);

        assertThat(beanFactory.getSingleton(TracingBootstrapPropertyNames.SETTINGS_BEAN_NAME))
                .isSameAs(settings);
        assertThatIllegalStateException()
                .isThrownBy(() -> registrar.register(beanFactory, settings))
                .withMessageContaining("already registered");
    }

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "HELM")
                .withProperty("platform.environment", "LOCAL");
    }
}
