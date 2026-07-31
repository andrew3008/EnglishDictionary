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
 * P5 §20 P5.6 — DISABLED no-discovery contract.
 *
 * <p>Proves that when tracing is disabled (via {@code platform.tracing.mode=DISABLED} or
 * {@code platform.tracing.configuration.enabled=false}), no JMX discovery, private-control
 * infrastructure or Actuator write surface is activated — regardless of authority or posture.
 *
 * <p>Covers:
 * <ul>
 *   <li>§1  DISABLED + HELM (all postures) — starts cleanly, no discovery beans</li>
 *   <li>§2  DISABLED + write authorities — fail-closed before any discovery attempt</li>
 *   <li>§3  Guard flags are false when disabled</li>
 *   <li>§4  configuration.enabled=false equivalent — same contracts as DISABLED mode</li>
 * </ul>
 */
class P56DisabledNoDiscoveryTest {

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingPolicyControlAutoConfiguration.class));

    private final ApplicationContextRunner guardRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class));

    // ── §1 DISABLED + HELM (all postures) ────────────────────────────────────

    /**
     * HELM is read-only; DISABLED+HELM must start cleanly without activating any
     * private-control or Actuator write infrastructure.
     */
    @ParameterizedTest(name = "DISABLED HELM no-discovery: posture={0}")
    @ValueSource(strings = {"LOCAL", "SHARED", "PRODUCTION"})
    void disabledWithHelm_noDiscoveryBeansCreated(String posture) {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=" + posture,
                        "platform.tracing.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // No JMX private-control infrastructure may be instantiated
                    assertThat(context).doesNotHaveBean(PrivateControlLocator.class);
                    assertThat(context).doesNotHaveBean(PrivateJmxTracingPolicySnapshotClient.class);
                    // No Actuator write surface may be created
                    assertThat(context).doesNotHaveBean(SpringActuatorTracingPolicyAdapter.class);
                });
    }

    // ── §2 DISABLED + write authorities — fail-closed ────────────────────────

    /**
     * CONFIG_SERVER and ACTUATOR are write authorities; combining them with DISABLED must
     * fail fast before any discovery attempt can occur.
     */
    @ParameterizedTest(name = "DISABLED write authority fails fast: authority={0}")
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void disabledWithWriteAuthority_failsClosedBeforeDiscovery(String authority) {
        // Fail-closed at authority BFPP; bean absence cannot be queried on a failed
        // ApplicationContextRunner context — guard exception is the executable proof.
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "platform.tracing.mode=DISABLED")
                .run(context -> assertGuardFailure(context, "Tracing disabled is incompatible"));
    }

    // ── §3 Guard flags are false when disabled ────────────────────────────────

    @Test
    void disabledWithHelm_guardFlagsAreFalse() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL",
                        "platform.tracing.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isTracingEnabled()).isFalse();
                    assertThat(guard.isActuatorMutationSurfaceAllowed()).isFalse();
                });
    }

    @Test
    void disabledWithHelm_sharedPosture_guardFlagsAreFalse() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=SHARED",
                        "platform.tracing.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isTracingEnabled()).isFalse();
                    assertThat(guard.isActuatorMutationSurfaceAllowed()).isFalse();
                });
    }

    @Test
    void disabledWithHelm_productionPosture_guardFlagsAreFalse() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=PRODUCTION",
                        "platform.tracing.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isTracingEnabled()).isFalse();
                    assertThat(guard.isActuatorMutationSurfaceAllowed()).isFalse();
                });
    }

    // ── §4 configuration.enabled=false — same contracts ──────────────────────

    /**
     * {@code platform.tracing.configuration.enabled=false} is the property-based equivalent
     * of DISABLED mode and must enforce the same no-discovery contract.
     */
    @ParameterizedTest(name = "configuration.enabled=false HELM no-discovery: posture={0}")
    @ValueSource(strings = {"LOCAL", "SHARED", "PRODUCTION"})
    void configEnabledFalseWithHelm_noDiscoveryBeansCreated(String posture) {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=" + posture,
                        "platform.tracing.configuration.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PrivateControlLocator.class);
                    assertThat(context).doesNotHaveBean(PrivateJmxTracingPolicySnapshotClient.class);
                    assertThat(context).doesNotHaveBean(SpringActuatorTracingPolicyAdapter.class);
                });
    }

    @ParameterizedTest(name = "configuration.enabled=false write authority fails fast: authority={0}")
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void configEnabledFalseWithWriteAuthority_failsClosedBeforeDiscovery(String authority) {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "platform.tracing.configuration.enabled=false")
                .run(context -> assertGuardFailure(context, "Tracing disabled is incompatible"));
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
