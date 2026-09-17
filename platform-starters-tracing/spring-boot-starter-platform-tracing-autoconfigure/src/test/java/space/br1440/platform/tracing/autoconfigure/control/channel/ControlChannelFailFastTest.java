package space.br1440.platform.tracing.autoconfigure.control.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

class ControlChannelFailFastTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingControlChannelAutoConfiguration.class));

    @Test
    void missingChannelFailsClosed() {
        // given
        // when
        // then
        runner.run(context -> assertStartupFailure(context, "Missing required property 'platform.tracing.control.channel'"));
    }

    @Test
    void emptyChannelFailsClosed() {
        // given
        // when
        // then
        runner.withPropertyValues("platform.tracing.control.channel=")
                .run(context -> assertStartupFailure(context, "Missing required property 'platform.tracing.control.channel'"));
    }

    @Test
    void unknownChannelFailsClosed() {
        // given
        // when
        // then
        runner.withPropertyValues("platform.tracing.control.channel=DEV_ACTUATOR")
                .run(context -> assertStartupFailure(context, "Invalid property 'platform.tracing.control.channel'"));
    }

    @Test
    void helmIsRejectedWithoutMappingToStatic() {
        // given
        // when
        // then
        runner.withPropertyValues("platform.tracing.control.channel=HELM")
                .run(context -> assertStartupFailure(context, "Invalid property 'platform.tracing.control.channel'='HELM'"));
    }

    @Test
    void obsoleteAuthorityFailsWithReplacementHint() {
        // given
        // when
        // then
        runner.withPropertyValues("platform.tracing.configuration.authority=HELM")
                .run(context -> assertStartupFailure(context, "platform.tracing.control.channel"));
    }

    @Test
    void dualKeysFailWithoutPrecedence() {
        // given
        // when
        // then
        runner.withPropertyValues(
                        "platform.tracing.control.channel=STATIC",
                        "platform.tracing.configuration.authority=HELM")
                .run(context -> assertStartupFailure(
                        context,
                        "Obsolete Spring-only property 'platform.tracing.configuration.authority'"));
    }

    @Test
    void obsoleteAuthorityEnvFailsThroughSystemEnvironmentPropertySource() {
        // given
        // when
        // then
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                "systemEnvironment",
                                Map.of("PLATFORM_TRACING_CONFIGURATION_AUTHORITY", "HELM"))))
                .run(context -> assertStartupFailure(context, "platform.tracing.control.channel"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATIC", "CONFIG_SERVER", "ACTUATOR"})
    void legalChannelsStart(String channel) {
        // given
        // when
        // then
        runner.withPropertyValues("platform.tracing.control.channel=" + channel)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingBootstrapSettings settings = context.getBean(TracingBootstrapSettings.class);
                    assertThat(settings.controlChannel()).isEqualTo(TracingControlChannel.valueOf(channel));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void disabledWriteChannelFailsClosed(String channel) {
        // given
        // when
        // then
        runner.withPropertyValues(
                        "platform.tracing.control.channel=" + channel,
                        "platform.tracing.enabled=false")
                .run(context -> assertStartupFailure(context, "Tracing disabled is incompatible"));
    }

    @Test
    void disabledStaticStarts() {
        // given
        // when
        // then
        runner.withPropertyValues(
                        "platform.tracing.control.channel=STATIC",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TracingBootstrapSettings.class).tracingEnabled()).isFalse();
                });
    }

    private static void assertStartupFailure(AssertableApplicationContext context, String messageFragment) {
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
