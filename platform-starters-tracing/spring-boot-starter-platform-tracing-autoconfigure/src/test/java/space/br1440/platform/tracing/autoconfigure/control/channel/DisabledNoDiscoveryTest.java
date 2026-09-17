package space.br1440.platform.tracing.autoconfigure.control.channel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;

class DisabledNoDiscoveryTest {

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingSettingsControlAutoConfiguration.class));

    private final ApplicationContextRunner bootstrapRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingControlChannelAutoConfiguration.class));

    @Test
    void disabledWithStatic_noDiscoveryBeansCreated() {
        // given
        // when
        // then
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.control.channel=STATIC",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @ParameterizedTest(name = "disabled write channel fails fast: channel={0}")
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void disabledWithWriteChannel_failsClosedBeforeDiscovery(String channel) {
        // given
        // when
        // then
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.control.channel=" + channel,
                        "platform.tracing.enabled=false")
                .run(context -> assertGuardFailure(context, "Tracing disabled is incompatible"));
    }

    @Test
    void disabledWithStatic_bootstrapFlagsAreFalse() {
        // given
        // when
        // then
        bootstrapRunner
                .withPropertyValues(
                        "platform.tracing.control.channel=STATIC",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TracingBootstrapSettings.class).tracingEnabled()).isFalse();
                });
    }

    @SuppressWarnings("SameParameterValue")
    private static void assertGuardFailure(AssertableApplicationContext context, String messageFragment) {
        assertThat(context).hasFailed();
        Throwable failure = context.getStartupFailure();
        assertThat(failure).isNotNull();
        assertThat(failure)
                .matches(throwable ->
                        throwable instanceof TracingConfigurationGuardException
                                || throwable.getCause() instanceof TracingConfigurationGuardException);
        assertThat(failure.getMessage() + " / "
                + (failure.getCause() == null ? "" : failure.getCause().getMessage()))
                .contains(messageFragment);
    }
}
