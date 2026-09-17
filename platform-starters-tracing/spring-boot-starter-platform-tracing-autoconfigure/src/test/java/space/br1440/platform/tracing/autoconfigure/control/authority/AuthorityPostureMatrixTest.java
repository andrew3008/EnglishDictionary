package space.br1440.platform.tracing.autoconfigure.control.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.UUID;
import javax.management.MBeanServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

    private final ApplicationContextRunner guardRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class));

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingSettingsControlAutoConfiguration.class));

    @ParameterizedTest(name = "legal: authority={0}")
    @ValueSource(strings = {"HELM", "CONFIG_SERVER", "ACTUATOR"})
    void legalAuthority_startsAndExposesGuard(String authority) {
        guardRunner
                .withPropertyValues("platform.tracing.configuration.authority=" + authority)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TracingConfigurationAuthorityGuard.class);
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.authority().name()).isEqualTo(authority);
                });
    }

    @Test
    void actuatorAuthorityStartsWithoutEnvironmentGate() {
        guardRunner
                .withPropertyValues("platform.tracing.configuration.authority=ACTUATOR")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.authority().name()).isEqualTo("ACTUATOR");
                });
    }

    @Test
    void helmAuthorityJmxControlBeansAbsent() {
        actuatorStackRunner
                .withPropertyValues("platform.tracing.configuration.authority=HELM")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void configServerAuthorityJmxControlPresentWithoutActuatorWrite() throws Exception {
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
                    .withPropertyValues("platform.tracing.configuration.authority=CONFIG_SERVER")
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

    @ParameterizedTest(name = "DISABLED incompatible: authority={0}")
    @ValueSource(strings = {"CONFIG_SERVER", "ACTUATOR"})
    void disabledMode_withWriteAuthority_failsClosed(String authority) {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String msg = context.getStartupFailure().getMessage()
                            + " / " + (context.getStartupFailure().getCause() == null ? ""
                                    : context.getStartupFailure().getCause().getMessage());
                    assertThat(msg).containsIgnoringCase("Tracing disabled is incompatible");
                });
    }

    @Test
    void helmDisabledMode_starts() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.isTracingEnabled()).isFalse();
                });
    }

    @ParameterizedTest(name = "zero-write proof: authority={0}")
    @ValueSource(strings = {"HELM", "CONFIG_SERVER"})
    void readOnlyAuthority_actuatorAdapterPhysicallyAbsent(String authority) {
        actuatorStackRunner
                .withPropertyValues("platform.tracing.configuration.authority=" + authority)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void helmDisabled_actuatorWriteAdapterPhysicallyAbsent() {
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void leftoverPlatformEnvironmentDoesNotChangeAuthority() {
        guardRunner
                .withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL",
                        "deployment.environment.name=production")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TracingConfigurationAuthorityGuard guard =
                            context.getBean(TracingConfigurationAuthorityGuard.class);
                    assertThat(guard.authority().name()).isEqualTo("HELM");
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
                .withPropertyValues("platform.tracing.configuration.authority=CONFIG_SERVER");
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
}
