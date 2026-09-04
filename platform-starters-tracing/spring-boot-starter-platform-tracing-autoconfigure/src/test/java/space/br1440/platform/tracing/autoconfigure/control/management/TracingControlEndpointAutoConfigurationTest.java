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
    void actuatorLocalRegistersEndpointAndMutationService() {
        runner.withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=LOCAL")
                .run(context -> {
                    assertThat(context).hasSingleBean(TracingControlEndpoint.class);
                    assertThat(context).hasSingleBean(TracingSettingsMutationService.class);
                    assertThat(context)
                            .hasSingleBean(ActuatorAgentReadinessProbe.class);
                });
    }

    @Test
    void nonLocalAndNonActuatorPosturesHaveNoWriteSurface() {
        assertAbsent("ACTUATOR", "SHARED");
        assertAbsent("ACTUATOR", "PRODUCTION");
        assertAbsent("HELM", "LOCAL");
        assertAbsent("CONFIG_SERVER", "LOCAL");
    }

    private void assertAbsent(String authority, String environment) {
        runner.withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=" + environment)
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
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
        return () -> Optional.of(new AcceptedSettingsWithSequence(1L, "test-runtime", settings));
    }
}
