package space.br1440.platform.tracing.autoconfigure.control.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;

class TracingControlEndpointAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingSettingsControlAutoConfiguration.class))
            .withBean(CompleteTracingSettingsReader.class, this::reader)
            .withBean(TracingSettingsApplier.class,
                    () -> (expectedBase, snapshot, source) -> TracingSettingsDeliveryResult.APPLIED);

    @Test
    void actuatorRegistersEndpointAndMutationService() {
        runner.withPropertyValues("platform.tracing.control.channel=ACTUATOR")
                .run(context -> {
                    assertThat(context).hasSingleBean(TracingControlEndpoint.class);
                    assertThat(context).hasSingleBean(TracingSettingsMutationService.class);
                    assertThat(context)
                            .hasSingleBean(ActuatorAgentReadinessProbe.class);
                });
    }

    @Test
    void leftoverPlatformEnvironmentDoesNotDisableActuator() {
        // given
        // when
        // then
        runner.withPropertyValues(
                        "platform.tracing.control.channel=ACTUATOR",
                        "platform.environment=PRODUCTION")
                .run(context -> {
                    assertThat(context).hasSingleBean(TracingControlEndpoint.class);
                    assertThat(context).hasSingleBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void nonActuatorChannelsHaveNoWriteSurface() {
        // given
        // when
        // then
        assertAbsent("STATIC");
        assertAbsent("CONFIG_SERVER");
    }

    private void assertAbsent(String channel) {
        runner.withPropertyValues("platform.tracing.control.channel=" + channel)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TracingControlEndpoint.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    private CompleteTracingSettingsReader reader() {
        CompleteTracingSettings settings = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
        return () -> Optional.of(new AcceptedSettingsWithSequence(
                1L,
                "374ebf50-dbc0-401b-8308-8674788b28dd",
                settings
        ));
    }
}
