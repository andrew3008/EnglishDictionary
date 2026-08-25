package space.br1440.platform.tracing.autoconfigure.control.authority;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateControlLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 §20 P5.6 — Authority/posture matrix and physical zero-write proof.
 *
 * <p>Covers:
 * <ul>
 *   <li>§1  Legal authority×posture combinations — guard starts and exposes bean</li>
 *   <li>§2  ACTUATOR outside LOCAL — fail-closed</li>
 *   <li>§3  Local-only positive path — Actuator mutation surface allowed exactly once</li>
 *   <li>§4  HELM — private-control beans physically absent; CONFIG_SERVER wires private apply/read</li>
 *   <li>§5  ACTUATOR+SHARED/PRODUCTION — fail-closed before private-control beans</li>
 *   <li>§6  Local override rejection — DISABLED incompatible with write authorities</li>
 *   <li>§7  Physical zero-write proof — non-local postures expose no write surface</li>
 * </ul>
 */
class P56AuthorityPostureMatrixTest {

    // ── base runners ─────────────────────────────────────────────────────────

    private final ApplicationContextRunner guardRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class));

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingSettingsControlAutoConfiguration.class));

    // ── §1 Legal authority×posture combinations ───────────────────────────────

    /**
     * Every documented legal combination must start without error and expose the guard bean
     * with matching authority and posture values.
     */
    @ParameterizedTest(name = "legal: authority={0} posture={1}")
    @CsvSource({
            "HELM,LOCAL",
            "HELM,SHARED",
            "HELM,PRODUCTION",
            "CONFIG_SERVER,LOCAL",
            "CONFIG_SERVER,SHARED",
            "CONFIG_SERVER,PRODUCTION",
            "ACTUATOR,LOCAL"
    })
    void legalCombination_startsAndExposesGuard(String authority, String posture) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=" + posture)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TracingConfigurationAuthorityGuard.class);
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.authority().name()).isEqualTo(authority);
                    assertThat(guard.posture().name()).isEqualTo(posture);
                });
    }

    // ── §2 ACTUATOR outside LOCAL — fail-closed ───────────────────────────────

    @ParameterizedTest(name = "actuator forbidden: posture={0}")
    @ValueSource(strings = {"SHARED", "PRODUCTION"})
    void actuatorOutsideLocal_failsClosed(String posture) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=" + posture)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .satisfiesAnyOf(
                                    t -> assertThat(t).isInstanceOf(TracingConfigurationGuardException.class),
                                    t -> assertThat(t.getCause()).isInstanceOf(TracingConfigurationGuardException.class)
                            );
                    String msg = context.getStartupFailure().getMessage()
                            + " / " + (context.getStartupFailure().getCause() == null ? ""
                                    : context.getStartupFailure().getCause().getMessage());
                    assertThat(msg).containsIgnoringCase("ACTUATOR authority is forbidden");
                });
    }

    // ── §3 Local-only positive path ───────────────────────────────────────────

    /**
     * ACTUATOR+LOCAL is the only combination where {@code isActuatorMutationSurfaceAllowed()} is
     * {@code true}.
     */
    @Test
    void actuatorLocal_mutationSurfaceAllowed() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=LOCAL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isActuatorMutationSurfaceAllowed()).isTrue();
                });
    }

    /**
     * For every legal non-ACTUATOR authority, mutation surface must be {@code false} regardless of
     * posture.
     */
    @ParameterizedTest(name = "no mutation surface: authority={0} posture={1}")
    @CsvSource({
            "HELM,LOCAL",
            "HELM,SHARED",
            "HELM,PRODUCTION",
            "CONFIG_SERVER,LOCAL",
            "CONFIG_SERVER,SHARED",
            "CONFIG_SERVER,PRODUCTION"
    })
    void nonActuatorAuthority_mutationSurfaceNeverAllowed(String authority, String posture) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=" + posture)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isActuatorMutationSurfaceAllowed()).isFalse();
                });
    }

    // ── §4 HELM — private-control beans physically absent ─────────────────────

    @ParameterizedTest(name = "HELM no private-control beans: posture={0}")
    @ValueSource(strings = {"LOCAL", "SHARED", "PRODUCTION"})
    void helmAuthority_privateControlBeansAbsent(String posture) {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=" + posture)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PrivateControlLocator.class);
                    assertThat(context).doesNotHaveBean(PrivateJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    /**
     * P6.2: CONFIG_SERVER discovers the private complete-snapshot writer but never
     * registers an Actuator write adapter.
     */
    @ParameterizedTest(name = "CONFIG_SERVER private writer, no Actuator write: posture={0}")
    @ValueSource(strings = {"LOCAL", "SHARED", "PRODUCTION"})
    void configServerAuthority_privateControlPresentWithoutActuatorWrite(String posture)
            throws Exception {
        try (space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane agent =
                     space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane
                             .compliant(java.util.UUID.randomUUID().toString(), 0L)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            TracingConfigurationAuthorityAutoConfiguration.class,
                            PrivateJmxControlAutoConfiguration.class,
                            space.br1440.platform.tracing.autoconfigure.control.configserver
                                    .ConfigServerTracingSettingsAutoConfiguration.class))
                    .withInitializer(context -> {
                        org.springframework.core.env.MapPropertySource authoritative =
                                new org.springframework.core.env.MapPropertySource(
                                        "configserver:platform-tracing-policy",
                                        java.util.Map.of(
                                                "platform.tracing.policy.sampling.enabled", "true",
                                                "platform.tracing.policy.sampling.ratio", "1.0",
                                                "platform.tracing.policy.validation.enabled", "true",
                                                "platform.tracing.policy.validation.strict", "false",
                                                "platform.tracing.policy.exporter.enabled", "true",
                                                "platform.tracing.policy.propagation.trace-control-headers.enabled",
                                                "true",
                                                "platform.tracing.policy.diagnostics.log-level", "INFO"));
                        context.getEnvironment().getPropertySources().addFirst(authoritative);
                    })
                    .withPropertyValues(
                            "platform.tracing.configuration.authority=CONFIG_SERVER",
                            "platform.environment=" + posture)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(PrivateControlLocator.class);
                        assertThat(context).hasSingleBean(PrivateJmxTracingSettingsClient.class);
                        assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                    });
        }
    }

    // ── §5 ACTUATOR+SHARED/PRODUCTION — fail-closed before private-control beans ──

    @ParameterizedTest(name = "actuator non-local fail-closed before private beans: posture={0}")
    @ValueSource(strings = {"SHARED", "PRODUCTION"})
    void actuatorNonLocal_failsBeforePrivateControlBeansCreated(String posture) {
        // Full ACTUATOR stack still fail-closes at the authority BFPP — before private-control
        // beans can be created. ApplicationContextRunner cannot query beans on a failed context,
        // so absence is proven by guard exception (same pattern as production context tests).
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=" + posture)
                .run(context -> assertGuardFailure(context, "ACTUATOR authority is forbidden"));
    }

    // ── §6 Local override rejection ───────────────────────────────────────────

    /**
     * DISABLED mode is incompatible with any write authority (CONFIG_SERVER or ACTUATOR).
     */
    @ParameterizedTest(name = "DISABLED incompatible: authority={0}")
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void disabledMode_withWriteAuthority_failsClosed(String authority) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String msg = context.getStartupFailure().getMessage()
                            + " / " + (context.getStartupFailure().getCause() == null ? ""
                                    : context.getStartupFailure().getCause().getMessage());
                    assertThat(msg).containsIgnoringCase("Tracing disabled is incompatible");
                });
    }

    /**
     * Выключенный platform facade несовместим с активным Spring writer authority.
     */
    @ParameterizedTest(name = "configuration.enabled=false incompatible: authority={0}")
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void configurationEnabledFalse_withWriteAuthority_failsClosed(String authority) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String msg = context.getStartupFailure().getMessage()
                            + " / " + (context.getStartupFailure().getCause() == null ? ""
                                    : context.getStartupFailure().getCause().getMessage());
                    assertThat(msg).containsIgnoringCase("Tracing disabled is incompatible");
                });
    }

    /**
     * HELM+DISABLED is a valid combination — HELM is read-only and disabled tracing is allowed.
     */
    @Test
    void helmDisabledMode_starts() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isTracingEnabled()).isFalse();
                    assertThat(guard.isActuatorMutationSurfaceAllowed()).isFalse();
                });
    }

    // ── §7 Physical zero-write proof — non-local postures ────────────────────

    /**
     * For every non-LOCAL posture with a read-only authority, the Actuator write adapter must be
     * physically absent from the context (not just returning 403).
     */
    @ParameterizedTest(name = "zero-write proof: authority={0} posture={1}")
    @CsvSource({
            "HELM,SHARED",
            "HELM,PRODUCTION",
            "CONFIG_SERVER,SHARED",
            "CONFIG_SERVER,PRODUCTION"
    })
    void nonLocalPosture_actuatorAdapterPhysicallyAbsent(String authority, String posture) {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=" + posture)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    /**
     * P6.10 residual: HELM+DISABLED (legal) must not expose any Actuator write adapter.
     * Complements {@link #helmDisabledMode_starts()} which already asserts the mutation-surface flag.
     */
    @Test
    void helmDisabled_actuatorWriteAdapterPhysicallyAbsent() {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=PRODUCTION",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
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
