package space.br1440.platform.tracing.autoconfigure.control.authority;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
                .withPropertyValues("platform.environment=LOCAL")
                .run(context -> assertGuardFailure(context, "Missing platform.tracing.configuration.authority"));
    }

    @Test
    void invalidAuthority_failsClosed() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=DEV_ACTUATOR",
                        "platform.environment=LOCAL")
                .run(context -> assertGuardFailure(context, "Invalid platform.tracing.configuration.authority"));
    }

    @Test
    void missingEnvironment_failsClosed() {
        guardRunner
                .withPropertyValues("platform.tracing.configuration.authority=HELM")
                .run(context -> assertGuardFailure(context, "Unknown platform.environment"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "development", "prod", "test", "staging", "LOACL"})
    void invalidOrAliasedEnvironment_failsClosed(String rawEnvironment) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=" + rawEnvironment)
                .run(context -> assertGuardFailure(context, "Unknown platform.environment"));
    }

    @ParameterizedTest
    @CsvSource({
            "HELM,LOCAL",
            "HELM,SHARED",
            "HELM,PRODUCTION",
            "CONFIG_SERVER,LOCAL",
            "CONFIG_SERVER,SHARED",
            "CONFIG_SERVER,PRODUCTION",
            "ACTUATOR,LOCAL"
    })
    void legalAuthorityPostureCombinations_startAndExposeGuardBean(String authority, String posture) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=" + posture)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean(
                            TracingConfigurationAuthorityAutoConfiguration.GUARD_BEAN_NAME);
                    TracingConfigurationAuthorityGuard guard = context.getBean(
                            TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.authority().name()).isEqualTo(authority);
                    assertThat(guard.posture().name()).isEqualTo(posture);
                    assertThat(guard.isActuatorMutationSurfaceAllowed())
                            .isEqualTo("ACTUATOR".equals(authority) && "LOCAL".equals(posture));
                });
    }

    @ParameterizedTest
    @CsvSource({
            "ACTUATOR,SHARED",
            "ACTUATOR,PRODUCTION"
    })
    void actuatorOutsideLocal_failsClosed(String authority, String posture) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=" + posture)
                .run(context -> assertGuardFailure(context, "ACTUATOR authority is forbidden"));
    }

    @Test
    void disabledModeWithHelm_starts() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard = context.getBean(
                            TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isTracingEnabled()).isFalse();
                    assertThat(guard.isActuatorMutationSurfaceAllowed()).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void disabledModeWithWriteAuthority_failsClosed(String authority) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "platform.tracing.enabled=false")
                .run(context -> assertGuardFailure(context, "Tracing disabled is incompatible"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void configurationEnabledFalseWithWriteAuthority_failsClosed(String authority) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "platform.tracing.enabled=false")
                .run(context -> assertGuardFailure(context, "Tracing disabled is incompatible"));
    }

    @Test
    void helmDoesNotActivateActuatorJmxControlBeans() {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void configServerDoesNotActivateActuatorWriteAdapterFromActuatorAutoConfig() {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=CONFIG_SERVER",
                        "platform.environment=LOCAL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void actuatorSharedFailsBeforeJmxControlBeans() {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=SHARED")
                .run(context -> assertGuardFailure(context, "ACTUATOR authority is forbidden"));
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
