package space.br1440.platform.tracing.autoconfigure.control.authority;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;

class TracingConfigurationAuthorityGuardContextTest {

    private final ApplicationContextRunner guardRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class));

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingSettingsControlAutoConfiguration.class));

    @Test
    void missingAuthority_failsClosed() {
        guardRunner
                .run(context -> assertGuardFailure(context, "Missing platform.tracing.configuration.authority"));
    }

    @Test
    void emptyAuthority_failsClosed() {
        guardRunner
                .withPropertyValues("platform.tracing.configuration.authority=")
                .run(context -> assertGuardFailure(context, "Missing platform.tracing.configuration.authority"));
    }

    @Test
    void invalidAuthority_failsClosed() {
        guardRunner
                .withPropertyValues("platform.tracing.configuration.authority=DEV_ACTUATOR")
                .run(context -> assertGuardFailure(context, "Invalid platform.tracing.configuration.authority"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HELM", "CONFIG_SERVER", "ACTUATOR"})
    void legalAuthorities_startAndExposeGuardBean(String authority) {
        guardRunner
                .withPropertyValues("platform.tracing.configuration.authority=" + authority)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean(
                            TracingConfigurationAuthorityAutoConfiguration.GUARD_BEAN_NAME);
                    TracingConfigurationAuthorityGuard guard = context.getBean(
                            TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.authority().name()).isEqualTo(authority);
                });
    }

    @Test
    void platformEnvironmentDoesNotSelectAuthority() {
        guardRunner
                .withPropertyValues("platform.environment=LOCAL")
                .run(context -> assertGuardFailure(context, "Missing platform.tracing.configuration.authority"));
    }

    @Test
    void disabledModeWithHelm_starts() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard = context.getBean(
                            TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isTracingEnabled()).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void disabledModeWithWriteAuthority_failsClosed(String authority) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.tracing.enabled=false")
                .run(context -> assertGuardFailure(context, "Tracing disabled is incompatible"));
    }

    @Test
    void helmDoesNotActivateActuatorJmxControlBeans() {
        actuatorStackRunner
                .withPropertyValues("platform.tracing.configuration.authority=HELM")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void configServerDoesNotActivateActuatorWriteAdapterFromActuatorAutoConfig() {
        actuatorStackRunner
                .withPropertyValues("platform.tracing.configuration.authority=CONFIG_SERVER")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void actuatorAuthorityStartsWithoutLocalEnvironment() {
        guardRunner
                .withPropertyValues("platform.tracing.configuration.authority=ACTUATOR")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard = context.getBean(
                            TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.authority().name()).isEqualTo("ACTUATOR");
                });
    }

    private static void assertGuardFailure(AssertableApplicationContext context, String messageFragment) {
        assertThat(context).hasFailed();
        Throwable failure = context.getStartupFailure();
        assertThat(failure).isNotNull();
        assertThat(failure)
                .as("startup failure must be or wrap TracingConfigurationGuardException")
                .matches(throwable ->
                        throwable instanceof TracingConfigurationGuardException
                                || throwable.getCause() instanceof TracingConfigurationGuardException);
        assertThat(failure.getMessage() + " / "
                + (failure.getCause() == null ? "" : failure.getCause().getMessage()))
                .contains(messageFragment);
    }
}
