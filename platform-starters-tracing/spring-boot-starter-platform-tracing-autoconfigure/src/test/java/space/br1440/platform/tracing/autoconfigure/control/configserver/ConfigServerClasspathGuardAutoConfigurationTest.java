package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.core.env.MapPropertySource;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;

class ConfigServerClasspathGuardAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    ConfigServerTracingSettingsAutoConfiguration.class,
                    ConfigServerClasspathGuardAutoConfiguration.class));

    @Test
    void configServerWithoutEnvironmentChangeEventFailsClosed() {
        // given
        ApplicationContextRunner missingCloud = runner
                .withClassLoader(new FilteredClassLoader(EnvironmentChangeEvent.class))
                .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER");

        // when / then
        missingCloud.run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertThat(failure).isNotNull();
            assertThat(failure)
                    .matches(throwable ->
                            throwable instanceof TracingConfigurationGuardException
                                    || throwable.getCause() instanceof TracingConfigurationGuardException);
            assertThat(failure.getMessage() + " / "
                    + (failure.getCause() == null ? "" : failure.getCause().getMessage()))
                    .contains(ConfigServerClasspathGuardAutoConfiguration.DELIVERY_UNAVAILABLE)
                    .contains("EnvironmentChangeEvent")
                    .contains("Action:");
        });
    }

    @Test
    void missingDeliveryBeanFailsWhenCloudContextIsPresent() {
        // given
        ApplicationContextRunner guardOnly = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingControlChannelAutoConfiguration.class,
                        ConfigServerClasspathGuardAutoConfiguration.class))
                .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER");

        // when / then
        guardOnly.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(TracingConfigurationGuardException.class)
                    .hasMessageContaining(ConfigServerClasspathGuardAutoConfiguration.DELIVERY_UNAVAILABLE)
                    .hasMessageContaining("did not register a delivery bean")
                    .hasMessageContaining("Action:");
        });
    }

    @Test
    void configServerWithDeliveryBeanStarts() {
        // given
        RecordingSettingsWriter writer = new RecordingSettingsWriter(() -> Optional.of(
                AcceptedSettingsTestFixtures.configServerAccepted(
                        0L,
                        "66f8525d-95e6-4cf8-a2ca-d384e6fd7b3c",
                        CompleteTracingSettings.builder()
                                .samplingEnabled(true)
                                .samplingRatio(1.0d)
                                .samplingRouteRatios(Map.of())
                                .validationLevel("WARNING")
                                .exporterEnabled(true)
                                .traceControlHeadersEnabled(true)
                                .diagnosticsLogLevel("INFO")
                                .build())));

        // when / then
        runner.withBean(TracingSettingsWriter.class, () -> writer)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("configserver:guard-test", authoritativePolicy())))
                .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConfigServerInitialDelivery.class);
                    assertThat(writer.applyCallCount()).isEqualTo(1);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "ACTUATOR"})
    void otherChannelsStartWithoutEnvironmentChangeEvent(String channel) {
        // given
        ApplicationContextRunner missingCloud = runner
                .withClassLoader(new FilteredClassLoader(EnvironmentChangeEvent.class))
                .withPropertyValues("platform.tracing.control.channel=" + channel);

        // when / then
        missingCloud.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ConfigServerTracingSettingsSynchronizer.class);
            assertThat(context).doesNotHaveBean(ConfigServerInitialDelivery.class);
        });
    }

    @Test
    void guardBacksOffWhenEnvironmentChangeEventIsPresent() {
        // given / when / then
        runner.withPropertyValues("platform.tracing.control.channel=NONE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ConfigServerClasspathGuardAutoConfiguration.class);
                });
    }

    private static Map<String, Object> authoritativePolicy() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("platform.tracing.policy.sampling.enabled", "true");
        values.put("platform.tracing.policy.sampling.ratio", "1.0");
        values.put("platform.tracing.policy.validation.level", "WARNING");
        values.put("platform.tracing.policy.exporter.enabled", "true");
        values.put("platform.tracing.policy.propagation.trace-control-headers.enabled", "true");
        values.put("platform.tracing.policy.diagnostics.log-level", "INFO");
        return values;
    }
}
