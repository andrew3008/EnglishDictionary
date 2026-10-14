package space.br1440.platform.tracing.autoconfigure.control.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;

class TracingControlEndpointAutoConfigurationTest {

    private final PlatformJmxTracingSettingsClient settingsClient = settingsClient();
    private final AcceptedTracingSettingsReader applicationPrimaryReader = mock(AcceptedTracingSettingsReader.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingSettingsControlAutoConfiguration.class))
            .withBean(PlatformJmxTracingSettingsClient.class, () -> settingsClient)
            .withBean(
                    "applicationPrimaryReader",
                    AcceptedTracingSettingsReader.class,
                    () -> applicationPrimaryReader,
                    beanDefinition -> beanDefinition.setPrimary(true));

    @Test
    void actuatorUsesFinalClientDespitePrimaryInterfaceStub() {
        // given
        // when
        runner.withPropertyValues("platform.tracing.control.channel=ACTUATOR")
                .run(context -> {
                    // then
                    assertThat(context).hasSingleBean(TracingControlEndpoint.class);
                    assertThat(context).hasSingleBean(TracingSettingsMutationService.class);
                    assertThat(context)
                            .hasSingleBean(ActuatorAgentReadinessProbe.class);
                    verify(settingsClient).current();
                    verifyNoInteractions(applicationPrimaryReader);
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
        assertAbsent("NONE");
        assertAbsent("CONFIG_SERVER");
    }

    private void assertAbsent(String channel) {
        runner.withPropertyValues("platform.tracing.control.channel=" + channel)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TracingControlEndpoint.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    private static PlatformJmxTracingSettingsClient settingsClient() {
        PlatformJmxTracingSettingsClient client = mock(PlatformJmxTracingSettingsClient.class);
        CompleteTracingSettings settings = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
        when(client.current()).thenReturn(Optional.of(
                AcceptedSettingsTestFixtures.bootstrapAgentDefaults(
                        1L,
                        "374ebf50-dbc0-401b-8308-8674788b28dd",
                        settings
                )));
        return client;
    }
}
