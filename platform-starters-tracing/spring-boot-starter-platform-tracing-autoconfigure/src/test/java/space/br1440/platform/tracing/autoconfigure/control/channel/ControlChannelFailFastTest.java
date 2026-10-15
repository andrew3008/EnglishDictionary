package space.br1440.platform.tracing.autoconfigure.control.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

class ControlChannelFailFastTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingControlChannelAutoConfiguration.class));

    @Test
    void missingChannelFailsClosed() {
        runner.run(context -> assertStartupFailure(context, "Missing required property 'platform.tracing.control.channel'"));
    }

    @Test
    void emptyChannelFailsClosed() {
        runner.withPropertyValues("platform.tracing.control.channel=")
                .run(context -> assertStartupFailure(context, "Property 'platform.tracing.control.channel' must not be blank"));
    }

    @Test
    void unknownChannelFailsClosed() {
        runner.withPropertyValues("platform.tracing.control.channel=DEV_ACTUATOR")
                .run(context -> assertStartupFailure(context, "Invalid property 'platform.tracing.control.channel'"));
    }

    @Test
    void helmIsRejectedWithoutMappingToNone() {
        runner.withPropertyValues("platform.tracing.control.channel=HELM")
                .run(context -> assertStartupFailure(context, "Invalid property 'platform.tracing.control.channel'='HELM'"));
    }

    @Test
    void staticIsRejectedWithoutAlias() {
        runner.withPropertyValues("platform.tracing.control.channel=STATIC")
                .run(context -> assertStartupFailure(
                        context,
                        "Invalid property 'platform.tracing.control.channel'='STATIC'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "CONFIG_SERVER", "ACTUATOR"})
    void legalChannelsStart(String channel) {
        runner.withPropertyValues("platform.tracing.control.channel=" + channel)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingBootstrapSettings settings = context.getBean(TracingBootstrapSettings.class);
                    assertThat(settings.controlChannel()).isEqualTo(TracingControlChannel.valueOf(channel));
                });
    }

    @Test
    void disabledActuatorChannelFailsClosed() {
        runner.withPropertyValues(
                        "platform.tracing.control.channel=ACTUATOR",
                        "platform.tracing.enabled=false")
                .run(context -> assertStartupFailure(context, "Tracing disabled is incompatible"));
    }

    @Test
    void disabledNoneStarts() {
        runner.withPropertyValues(
                        "platform.tracing.control.channel=NONE",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TracingBootstrapSettings.class).tracingEnabled()).isFalse();
                });
    }

    @Test
    void disabledWithoutChannelStarts() {
        runner.withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingBootstrapSettings settings = context.getBean(TracingBootstrapSettings.class);
                    assertThat(settings.tracingEnabled()).isFalse();
                    assertThat(settings.controlChannel()).isEqualTo(TracingControlChannel.NONE);
                });
    }

    @Test
    void disabledFromConfigServerStarts() {
        // given
        MapPropertySource source = new MapPropertySource(
                "configserver:app",
                Map.of(
                        "platform.tracing.enabled", "false",
                        "platform.tracing.policy.sampling.ratio", "0.5"
                )
        );

        // when
        runner.withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(source))
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    TracingBootstrapSettings settings = context.getBean(TracingBootstrapSettings.class);
                    assertThat(settings.tracingEnabled()).isFalse();
                    assertThat(settings.controlChannel()).isEqualTo(TracingControlChannel.CONFIG_SERVER);
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
