package space.br1440.platform.tracing.autoconfigure.control.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;

class ControlChannelBootstrapLifecycleTest {

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingSettingsControlAutoConfiguration.class));

    @Test
    void environmentMutationAfterStartDoesNotCreateActuatorWriter() {
        // given
        actuatorStackRunner
                .withPropertyValues("platform.tracing.control.channel=NONE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);

                    // when
                    context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("post-start", Map.of(
                                    "platform.tracing.control.channel", "ACTUATOR")));

                    // then
                    assertThat(context.getEnvironment().getProperty("platform.tracing.control.channel"))
                            .isEqualTo("ACTUATOR");
                    assertThat(context.getBean(TracingBootstrapSettings.class).controlChannel())
                            .isEqualTo(TracingControlChannel.NONE);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

}
