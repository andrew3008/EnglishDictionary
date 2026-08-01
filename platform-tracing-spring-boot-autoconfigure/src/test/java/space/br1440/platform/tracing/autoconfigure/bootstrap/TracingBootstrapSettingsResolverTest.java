package space.br1440.platform.tracing.autoconfigure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class TracingBootstrapSettingsResolverTest {

    private final TracingBootstrapSettingsResolver resolver = new TracingBootstrapSettingsResolver();

    @Test
    void missingModeDefaultsToAgent() {
        assertThat(resolver.resolve(environment()).mode()).isEqualTo(TracingMode.AGENT);
    }

    @Test
    void modeIsCaseInsensitive() {
        assertThat(resolver.resolve(environment().withProperty("platform.tracing.mode", "agent")).mode())
                .isEqualTo(TracingMode.AGENT);
        assertThat(resolver.resolve(environment().withProperty("platform.tracing.mode", "dIsAbLeD")).mode())
                .isEqualTo(TracingMode.DISABLED);
    }

    @Test
    void blankAndUnknownModesFailFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(environment().withProperty("platform.tracing.mode", " ")))
                .withMessageContaining("must not be blank");
        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(environment().withProperty("platform.tracing.mode", "UNKNOWN")))
                .withMessageContaining("Accepted values");
    }

    @Test
    void configurationEnabledIsStrictBoolean() {
        assertThat(resolver.resolve(environment()
                .withProperty("platform.tracing.configuration.enabled", "false")).configurationEnabled())
                .isFalse();
        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(environment()
                        .withProperty("platform.tracing.configuration.enabled", "yes")))
                .withMessageContaining("Accepted values: true, false");
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
