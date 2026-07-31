package space.br1440.platform.tracing.autoconfigure.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.tracing.autoconfigure.configuration.actuator.SpringActuatorTracingPolicyAdapter;
import space.br1440.platform.tracing.autoconfigure.configuration.actuator.TracingPolicyControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Spring context proof that {@link TracingConfigurationAuthorityGuard} is wired into
 * production auto-configuration and enforces the full mode/authority/posture fail-fast matrix
 * before Actuator private-control beans can activate.
 */
class TracingConfigurationAuthorityGuardContextTest {

    private final ApplicationContextRunner guardRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class));

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingPolicyControlAutoConfiguration.class));

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
                        "platform.tracing.mode=DISABLED")
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
                        "platform.tracing.mode=DISABLED")
                .run(context -> assertGuardFailure(context, "Tracing disabled is incompatible"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void configurationEnabledFalseWithWriteAuthority_failsClosed(String authority) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "platform.tracing.configuration.enabled=false")
                .run(context -> assertGuardFailure(context, "Tracing disabled is incompatible"));
    }

    @Test
    void helmDoesNotActivateActuatorPrivateControlBeans() {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PrivateControlLocator.class);
                    assertThat(context).doesNotHaveBean(PrivateJmxTracingPolicySnapshotClient.class);
                    assertThat(context).doesNotHaveBean(SpringActuatorTracingPolicyAdapter.class);
                });
    }

    @Test
    void configServerDoesNotActivateActuatorWriteAdapterFromActuatorAutoConfig() {
        // Private JMX for CONFIG_SERVER is owned by PrivateJmxControlAutoConfiguration +
        // ConfigServerTracingPolicyAutoConfiguration. The ACTUATOR auto-config alone must not
        // register an Actuator write adapter (or private-control beans) under CONFIG_SERVER.
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=CONFIG_SERVER",
                        "platform.environment=LOCAL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PrivateControlLocator.class);
                    assertThat(context).doesNotHaveBean(PrivateJmxTracingPolicySnapshotClient.class);
                    assertThat(context).doesNotHaveBean(SpringActuatorTracingPolicyAdapter.class);
                });
    }

    @Test
    void actuatorSharedFailsBeforePrivateControlBeans() {
        // Full ACTUATOR auto-config stack still fail-closes at the authority BFPP —
        // before PrivateControlLocator / readiness / adapter beans can be created.
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
