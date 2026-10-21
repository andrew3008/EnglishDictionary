package space.br1440.platform.tracing.autoconfigure.control.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.management.MBeanServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerClasspathGuardAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerInitialDelivery;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingSettingsAdapter;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingSettingsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingSettingsSynchronizer;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxReadAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingControlEndpoint;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.status.SettingsAbsenceReason;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusAssembler;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

class ControlChannelPostureMatrixTest {

    private final ApplicationContextRunner bootstrapRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingControlChannelAutoConfiguration.class));

    private final ApplicationContextRunner actuatorStackRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingSettingsControlAutoConfiguration.class)
            );

    @ParameterizedTest(name = "legal: channel={0}")
    @ValueSource(strings = {"NONE", "CONFIG_SERVER", "ACTUATOR"})
    void legalChannelStartsAndExposesBootstrapSettings(String channel) {
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
    void noneChannelCreatesReadClientWithoutWriteComponents() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane ignored = FakeAgentPlatformJmxPlane.compliant(UUID.randomUUID().toString(), 0L)) {
            AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard();
            TracingDeploymentDiagnostics startupDiagnostics =
                    new AgentStartupVerifier(coherenceGuard, () -> "1.2.3")
                            .verify(true, true, false, true);

            // when
            // then
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            TracingControlChannelAutoConfiguration.class,
                            PlatformJmxReadAutoConfiguration.class,
                            PlatformJmxControlAutoConfiguration.class,
                            ConfigServerTracingSettingsAutoConfiguration.class,
                            TracingSettingsControlAutoConfiguration.class))
                    .withBean(AgentReadinessCoherenceGuard.class, () -> coherenceGuard)
                    .withBean(TracingDeploymentDiagnostics.class, () -> startupDiagnostics)
                    .withPropertyValues("platform.tracing.control.channel=NONE")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(AcceptedTracingSettingsReader.class)).hasSize(1);
                        assertThat(context.getBeansOfType(TracingSettingsWriter.class)).isEmpty();
                        assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                        assertThat(context.getBean(AcceptedTracingSettingsReader.class)
                                .getClass().getSimpleName()).isEqualTo("JmxAcceptedSettingsReader");
                        assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                        assertThat(context).doesNotHaveBean(TracingControlEndpoint.class);
                        assertThat(context).doesNotHaveBean(ConfigServerTracingSettingsAdapter.class);
                        assertThat(context).doesNotHaveBean(ConfigServerTracingSettingsSynchronizer.class);
                        assertThat(context).doesNotHaveBean(ConfigServerInitialDelivery.class);
                        assertThat(context).doesNotHaveBean("actuatorAgentReadinessProbe");
                    });
        }
    }

    @Test
    void configServerCreatesJmxWithoutActuatorWrite() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane ignored =
                     FakeAgentPlatformJmxPlane.compliant(UUID.randomUUID().toString(), 0L)) {
            AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard();
            TracingDeploymentDiagnostics startupDiagnostics =
                    new AgentStartupVerifier(coherenceGuard, () -> "1.2.3")
                            .verify(true, true, false, true);

            // when
            // then
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                                    TracingControlChannelAutoConfiguration.class,
                                    PlatformJmxReadAutoConfiguration.class,
                                    PlatformJmxControlAutoConfiguration.class,
                                    ConfigServerTracingSettingsAutoConfiguration.class
                            )
                    )
                    .withBean(AgentReadinessCoherenceGuard.class, () -> coherenceGuard)
                    .withBean(TracingDeploymentDiagnostics.class, () -> startupDiagnostics)
                    .withInitializer(context -> {
                        MapPropertySource authoritative =
                                new MapPropertySource(
                                        "configserver:platform-tracing-policy",
                                        Map.of(
                                                "platform.tracing.policy.sampling.enabled", "true",
                                                "platform.tracing.policy.sampling.ratio", "1.0",
                                                "platform.tracing.policy.validation.level", "WARNING",
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
                        assertThat(context.getBeansOfType(AcceptedTracingSettingsReader.class)).hasSize(1);
                        assertThat(context.getBeansOfType(TracingSettingsWriter.class)).hasSize(1);
                        assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
                        assertThat(context).hasSingleBean(ConfigServerTracingSettingsSynchronizer.class);
                        assertThat(context).hasSingleBean(ConfigServerTracingSettingsAdapter.class);
                        assertThat(context).hasSingleBean(ConfigServerInitialDelivery.class);
                    });
        }
    }

    @Test
    void configServerSynchronizerDoesNotDisplaceApplicationTaskExecutor() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane ignored =
                     FakeAgentPlatformJmxPlane.compliant(UUID.randomUUID().toString(), 0L)) {
            AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard();
            TracingDeploymentDiagnostics startupDiagnostics =
                    new AgentStartupVerifier(coherenceGuard, () -> "1.2.3")
                            .verify(true, true, false, true);

            // when / then
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            TracingControlChannelAutoConfiguration.class,
                            PlatformJmxReadAutoConfiguration.class,
                            PlatformJmxControlAutoConfiguration.class,
                            ConfigServerTracingSettingsAutoConfiguration.class,
                            TaskExecutionAutoConfiguration.class))
                    .withBean(AgentReadinessCoherenceGuard.class, () -> coherenceGuard)
                    .withBean(TracingDeploymentDiagnostics.class, () -> startupDiagnostics)
                    .withInitializer(context -> {
                        MapPropertySource authoritative =
                                new MapPropertySource(
                                        "configserver:platform-tracing-policy",
                                        Map.of(
                                                "platform.tracing.policy.sampling.enabled", "true",
                                                "platform.tracing.policy.sampling.ratio", "1.0",
                                                "platform.tracing.policy.validation.level", "WARNING",
                                                "platform.tracing.policy.exporter.enabled", "true",
                                                "platform.tracing.policy.propagation.trace-control-headers.enabled",
                                                "true",
                                                "platform.tracing.policy.diagnostics.log-level", "INFO"));
                        context.getEnvironment().getPropertySources().addFirst(authoritative);
                    })
                    .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasBean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME);
                        assertThat(context).hasSingleBean(ConfigServerTracingSettingsSynchronizer.class);
                    });
        }
    }

    @Test
    void actuatorCreatesExactlyOneReaderWriterPort() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane ignored =
                     FakeAgentPlatformJmxPlane.compliant(UUID.randomUUID().toString(), 0L)) {
            AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard();
            TracingDeploymentDiagnostics startupDiagnostics =
                    new AgentStartupVerifier(coherenceGuard, () -> "1.2.3")
                            .verify(true, true, false, true);

            // when
            // then
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            TracingControlChannelAutoConfiguration.class,
                            PlatformJmxReadAutoConfiguration.class,
                            PlatformJmxControlAutoConfiguration.class))
                    .withBean(AgentReadinessCoherenceGuard.class, () -> coherenceGuard)
                    .withBean(TracingDeploymentDiagnostics.class, () -> startupDiagnostics)
                    .withPropertyValues("platform.tracing.control.channel=ACTUATOR")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(PlatformJmxTracingSettingsClient.class);
                        assertThat(context.getBeansOfType(AcceptedTracingSettingsReader.class)).hasSize(1);
                        assertThat(context.getBeansOfType(TracingSettingsWriter.class)).hasSize(1);
                    });
        }
    }

    @Test
    void noneWithoutAgentFailsWithControlledAgentMissing() {
        // given
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingControlChannelAutoConfiguration.class,
                        TracingRuntimeAutoConfiguration.class,
                        PlatformJmxReadAutoConfiguration.class))
                .withPropertyValues("platform.tracing.control.channel=NONE");

        // when
        // then
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("CONTROLLED_AGENT_MISSING");
        });
    }

    @Test
    void disabledWithoutChannelCreatesNoSettingsPortsAndReportsTracingDisabled() {
        // given
        ApplicationContextRunner runner = disabledTopologyRunner()
                .withPropertyValues("platform.tracing.enabled=false");

        // when
        // then
        assertDisabledTopology(runner);
    }

    @Test
    void disabledNoneCreatesNoSettingsPortsAndReportsTracingDisabled() {
        // given
        ApplicationContextRunner runner = disabledTopologyRunner()
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "platform.tracing.control.channel=NONE");

        // when
        // then
        assertDisabledTopology(runner);
    }

    @Test
    void disabledFromConfigServerCreatesNoControlPlaneAndReportsTracingDisabled() {
        // given
        MapPropertySource source = new MapPropertySource(
                "configserver:platform-tracing-policy",
                Map.of(
                        "platform.tracing.enabled", "false",
                        "platform.tracing.policy.sampling.ratio", "0.5"
                )
        );
        ApplicationContextRunner runner = disabledTopologyRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigServerTracingSettingsAutoConfiguration.class,
                        ConfigServerClasspathGuardAutoConfiguration.class,
                        TracingSettingsControlAutoConfiguration.class
                ))
                .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(source));

        // when
        // then
        assertDisabledTopology(runner);
    }

    @Test
    void configServerWithoutAgentReadyFailsClosedBeforeJmxDiscovery() {
        // given
        MBeanServer server = mock(MBeanServer.class);
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        ApplicationContextRunner runner = configServerJmxStack(coherenceGuard, incompatibleStartupDiagnostics());

        // when
        // then
        runner.run(context -> assertStartupTrustFailureWithoutJmx(context, server));
    }

    @ParameterizedTest(name = "zero-write: channel={0}")
    @ValueSource(strings = {"NONE", "CONFIG_SERVER"})
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
    void disabledNoneHasNoActuatorWriter() {
        // given
        // when
        // then
        actuatorStackRunner
                .withPropertyValues(
                        "platform.tracing.control.channel=NONE",
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
                        "platform.tracing.control.channel=NONE",
                        "platform.environment=LOCAL",
                        "deployment.environment.name=production")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TracingBootstrapSettings.class).controlChannel())
                            .isEqualTo(TracingControlChannel.NONE);
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

    private static ApplicationContextRunner disabledTopologyRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                                TracingControlChannelAutoConfiguration.class,
                                TracingRuntimeAutoConfiguration.class,
                                PlatformJmxReadAutoConfiguration.class,
                                PlatformJmxControlAutoConfiguration.class,
                                TracingStatusAutoConfiguration.class
                        )
                )
                .withBean(PlatformTracingJmxClient.class, () -> {
                    PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
                    when(client.jmxContractDiagnostics()).thenReturn(List.of());
                    return client;
                });
    }

    private static void assertDisabledTopology(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AcceptedTracingSettingsReader.class)).isEmpty();
            assertThat(context.getBeansOfType(TracingSettingsWriter.class)).isEmpty();
            assertThat(context).doesNotHaveBean(ConfigServerTracingSettingsSynchronizer.class);
            assertThat(context).doesNotHaveBean(ConfigServerTracingSettingsAdapter.class);
            assertThat(context).doesNotHaveBean(ConfigServerInitialDelivery.class);
            assertThat(context).doesNotHaveBean(TracingSettingsMutationService.class);
            assertThat(context).hasSingleBean(TracingStatusAssembler.class);
            assertThat(context.getBean(TracingStatusAssembler.class).assemble().settings().reason())
                    .isEqualTo(SettingsAbsenceReason.TRACING_DISABLED);
        });
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
