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
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;

class DisabledNoDiscoveryTest {

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingSettingsControlAutoConfiguration.class));

    private final ApplicationContextRunner guardRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class));

    // ── §1 DISABLED + HELM во всех окружениях ────────────────────────────────

    /**
     * HELM доступен только для чтения, поэтому DISABLED+HELM запускается без компонентов
     * JMX-управления и записи Actuator.
     */
    @ParameterizedTest(name = "DISABLED HELM no-discovery: posture={0}")
    @ValueSource(strings = {"LOCAL", "SHARED", "PRODUCTION"})
    void disabledWithHelm_noDiscoveryBeansCreated(String posture) {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=" + posture,
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // Компоненты JMX-управления не должны создаваться.
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    // Поверхность записи Actuator не должна создаваться.
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    // ── §2 DISABLED + полномочия записи — блокировка ─────────────────────────

    /**
     * CONFIG_SERVER и ACTUATOR имеют полномочия записи, поэтому их сочетание с DISABLED
     * блокируется до любой попытки обнаружения.
     */
    @ParameterizedTest(name = "DISABLED write authority fails fast: authority={0}")
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void disabledWithWriteAuthority_failsClosedBeforeDiscovery(String authority) {
        // Ограничитель полномочий блокирует запуск до создания bean. В неуспешном контексте
        // отсутствие bean подтверждается исключением ограничителя.
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "platform.tracing.enabled=false")
                .run(context -> assertGuardFailure(context, "Tracing disabled is incompatible"));
    }

    // ── §3 Флаги ограничителя выключены в режиме DISABLED ────────────────────

    @Test
    void disabledWithHelm_guardFlagsAreFalse() {
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

    @Test
    void disabledWithHelm_sharedPosture_guardFlagsAreFalse() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=SHARED",
                        "platform.tracing.enabled=false")
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
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isTracingEnabled()).isFalse();
                    assertThat(guard.isActuatorMutationSurfaceAllowed()).isFalse();
                });
    }

    // ── §4 configuration.enabled=false — контракт отсутствия обнаружения ─────

    /**
     * {@code platform.tracing.enabled=false} отключает плоскость управления Spring,
     * но не отменяет отдельную проверку готовности агента и состава процесса.
     */
    @ParameterizedTest(name = "configuration.enabled=false HELM no-discovery: posture={0}")
    @ValueSource(strings = {"LOCAL", "SHARED", "PRODUCTION"})
    void configEnabledFalseWithHelm_noDiscoveryBeansCreated(String posture) {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=" + posture,
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @ParameterizedTest(name = "configuration.enabled=false write authority fails fast: authority={0}")
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void configEnabledFalseWithWriteAuthority_failsClosedBeforeDiscovery(String authority) {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "platform.tracing.enabled=false")
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
