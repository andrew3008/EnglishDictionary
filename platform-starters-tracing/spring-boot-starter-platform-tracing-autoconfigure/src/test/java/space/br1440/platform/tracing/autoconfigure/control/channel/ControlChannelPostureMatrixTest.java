package space.br1440.platform.tracing.autoconfigure.control.channel;

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
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingSettingsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

class ControlChannelPostureMatrixTest {

    private final ApplicationContextRunner bootstrapRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingControlChannelAutoConfiguration.class));

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingSettingsControlAutoConfiguration.class));

    @ParameterizedTest(name = "legal: channel={0}")
    @ValueSource(strings = {"STATIC", "CONFIG_SERVER", "ACTUATOR"})
    void legalChannelStartsAndExposesBootstrapSettings(String channel) {
        // given
        // when
        // then
        bootstrapRunner
                .withPropertyValues("platform.tracing.control.channel=" + channel)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TracingBootstrapSettings.class);
                    assertThat(context.getBean(TracingBootstrapSettings.class).controlChannel().name())
                            .isEqualTo(channel);
                });
    }

    @Test
    void staticChannelDoesNotCreateWriteBeans() {
        // given
        // when
        // then
        actuatorStackRunner
                .withPropertyValues("platform.tracing.control.channel=STATIC")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void configServerCreatesJmxWithoutActuatorWrite() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane ignored =
                     FakeAgentPlatformJmxPlane.compliant(UUID.randomUUID().toString(), 0L)) {
            AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard();
            TracingDeploymentDiagnostics startupDiagnostics =
                    new AgentStartupVerifier(coherenceGuard, "1.2.3")
                            .verify(true, true, false, true);

            // when
            // then
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            TracingControlChannelAutoConfiguration.class,
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
                    .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER")
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
        // given
        MBeanServer server = mock(MBeanServer.class);
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        ApplicationContextRunner runner = configServerJmxStack(coherenceGuard, incompatibleStartupDiagnostics());
        if (userClientPresent) {
            runner = runner.withBean(
                    PlatformJmxTracingSettingsClient.class,
                    () -> new PlatformJmxTracingSettingsClient(server, coherenceGuard));
        }

        // when
        // then
        runner.run(context -> assertStartupTrustFailureWithoutJmx(context, server));
    }

    @ParameterizedTest(name = "zero-write: channel={0}")
    @ValueSource(strings = {"STATIC", "CONFIG_SERVER"})
    void nonActuatorChannelHasNoActuatorWriter(String channel) {
        // given
        // when
        // then
        actuatorStackRunner
                .withPropertyValues("platform.tracing.control.channel=" + channel)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void disabledStaticHasNoActuatorWriter() {
        // given
        // when
        // then
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.control.channel=STATIC",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                });
    }

    @Test
    void leftoverPlatformKeysDoNotChangeSelectedChannel() {
        // given
        // when
        // then
        bootstrapRunner
                .withPropertyValues(
                        "platform.tracing.control.channel=STATIC",
                        "platform.environment=LOCAL",
                        "deployment.environment.name=production")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TracingBootstrapSettings.class).controlChannel())
                            .isEqualTo(TracingControlChannel.STATIC);
                });
    }

    private static ApplicationContextRunner configServerJmxStack(
            AgentReadinessCoherenceGuard coherenceGuard,
            TracingDeploymentDiagnostics startupDiagnostics) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingControlChannelAutoConfiguration.class,
                        PlatformJmxControlAutoConfiguration.class,
                        ConfigServerTracingSettingsAutoConfiguration.class))
                .withBean(AgentReadinessCoherenceGuard.class, () -> coherenceGuard)
                .withBean(TracingDeploymentDiagnostics.class, () -> startupDiagnostics)
                .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER");
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
