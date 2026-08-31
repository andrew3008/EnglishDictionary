package space.br1440.platform.tracing.autoconfigure.control.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.UUID;
import javax.management.MBeanServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingSettingsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

class AuthorityPostureMatrixTest {

    // ── Базовые средства запуска контекста ──────────────────────────────────

    private final ApplicationContextRunner guardRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class));

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingSettingsControlAutoConfiguration.class));

    // ── §1 Допустимые сочетания полномочий и окружения ───────────────────────

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

    // ── §2 ACTUATOR вне LOCAL — блокировка ───────────────────────────────────

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

    // ── §3 Позитивный сценарий только для LOCAL ──────────────────────────────

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
     * Для любых допустимых полномочий кроме ACTUATOR поверхность изменения выключена
     * независимо от окружения.
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

    // ── §4 HELM — физическое отсутствие компонентов JMX-управления ──────────

    @ParameterizedTest(name = "HELM без JMX-компонентов управления: posture={0}")
    @ValueSource(strings = {"LOCAL", "SHARED", "PRODUCTION"})
    void helmAuthorityJmxControlBeansAbsent(String posture) {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=" + posture)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    /**
     * P6.2: CONFIG_SERVER обнаруживает JMX-клиент полного снимка, но не регистрирует
     * адаптер записи Actuator.
     */
    @ParameterizedTest(name = "CONFIG_SERVER с JMX-клиентом без записи Actuator: posture={0}")
    @ValueSource(strings = {"LOCAL", "SHARED", "PRODUCTION"})
    void configServerAuthorityJmxControlPresentWithoutActuatorWrite(String posture)
            throws Exception {
        try (FakeAgentPlatformJmxPlane ignored =
                     FakeAgentPlatformJmxPlane.compliant(UUID.randomUUID().toString(), 0L)) {
            AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard();
            TracingDeploymentDiagnostics startupDiagnostics =
                    new AgentStartupVerifier(coherenceGuard, "1.2.3")
                            .verify(true, true, false, true);
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            TracingConfigurationAuthorityAutoConfiguration.class,
                            PlatformJmxControlAutoConfiguration.class,
                            ConfigServerTracingSettingsAutoConfiguration.class))
                    .withBean(AgentReadinessCoherenceGuard.class, () -> coherenceGuard)
                    .withBean(TracingDeploymentDiagnostics.class, () -> startupDiagnostics)
                    .withInitializer(context -> {
                        MapPropertySource authoritative =
                                new MapPropertySource(
                                        "configserver:platform-tracing-policy",
                                        Map.of(
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
                        assertThat(context).hasSingleBean(PlatformJmxTracingSettingsClient.class);
                        assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                    });
        }
    }

    @ParameterizedTest(name = "userClientPresent={0}")
    @ValueSource(booleans = {false, true})
    void configServerWithoutAgentReadyFailsClosedBeforeJmxDiscovery(boolean userClientPresent) {
        MBeanServer server = mock(MBeanServer.class);
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        ApplicationContextRunner runner = configServerJmxStack(coherenceGuard, incompatibleStartupDiagnostics());
        if (userClientPresent) {
            runner = runner.withBean(
                    PlatformJmxTracingSettingsClient.class,
                    () -> new PlatformJmxTracingSettingsClient(server, coherenceGuard));
        }

        runner.run(context -> assertStartupTrustFailureWithoutJmx(context, server));
    }

    // ── §5 ACTUATOR+SHARED/PRODUCTION — блокировка до JMX-компонентов ─────────

    @ParameterizedTest(name = "actuator non-local fail-closed before private beans: posture={0}")
    @ValueSource(strings = {"SHARED", "PRODUCTION"})
    void actuatorNonLocalFailsBeforeJmxControlBeansCreated(String posture) {
        // Полный стек ACTUATOR блокируется проверкой полномочий до создания JMX-компонентов.
        // В неуспешном контексте их отсутствие подтверждается исключением ограничителя.
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=" + posture)
                .run(context -> assertGuardFailure(context, "ACTUATOR authority is forbidden"));
    }

    // ── §6 Отклонение локального переопределения ─────────────────────────────

    /**
     * Режим DISABLED несовместим с любыми полномочиями записи: CONFIG_SERVER и ACTUATOR.
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
     * Выключенный платформенный фасад несовместим с активными полномочиями записи Spring.
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
     * HELM+DISABLED является допустимым сочетанием: HELM доступен только для чтения.
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

    // ── §7 Физическое отсутствие записи вне LOCAL ───────────────────────────

    /**
     * Во всех окружениях кроме LOCAL при полномочиях только для чтения адаптер записи Actuator
     * должен физически отсутствовать в контексте, а не только возвращать 403.
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
     * Остаточная проверка P6.10: допустимое сочетание HELM+DISABLED не предоставляет адаптер
     * записи Actuator и дополняет {@link #helmDisabledMode_starts()}.
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

    private static ApplicationContextRunner configServerJmxStack(
            AgentReadinessCoherenceGuard coherenceGuard,
            TracingDeploymentDiagnostics startupDiagnostics) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingConfigurationAuthorityAutoConfiguration.class,
                        PlatformJmxControlAutoConfiguration.class,
                        ConfigServerTracingSettingsAutoConfiguration.class))
                .withBean(AgentReadinessCoherenceGuard.class, () -> coherenceGuard)
                .withBean(TracingDeploymentDiagnostics.class, () -> startupDiagnostics)
                .withPropertyValues(
                        "platform.tracing.configuration.authority=CONFIG_SERVER",
                        "platform.environment=LOCAL");
    }

    private static TracingDeploymentDiagnostics incompatibleStartupDiagnostics() {
        return TracingDeploymentDiagnostics.withoutAgentData(
                true,
                true,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_VERSION_MISMATCH");
    }

    private static void assertStartupTrustFailureWithoutJmx(
            AssertableApplicationContext context,
            MBeanServer server) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_STARTUP_TRUST_NOT_ESTABLISHED")
                .hasMessageContaining("EXTENSION_INCOMPATIBLE");
        verifyNoInteractions(server);
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
