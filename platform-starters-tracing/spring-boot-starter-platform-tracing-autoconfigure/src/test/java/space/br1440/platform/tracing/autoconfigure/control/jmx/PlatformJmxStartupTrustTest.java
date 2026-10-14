package space.br1440.platform.tracing.autoconfigure.control.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import javax.management.MBeanServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

class PlatformJmxStartupTrustTest {

    @ParameterizedTest
    @EnumSource(value = AgentRuntimeState.class, mode = EnumSource.Mode.EXCLUDE, names = "AGENT_READY")
    void controlClientRejectsEveryNonReadyStartupState(AgentRuntimeState runtimeState) {
        MBeanServer server = mock(MBeanServer.class);
        controlRunner(server, runtimeState)
                .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(TracingConfigurationGuardException.class)
                            .hasMessageContaining("JMX_CLIENT_STARTUP_TRUST_NOT_ESTABLISHED")
                            .hasMessageContaining("runtimeState=" + runtimeState);
                    verifyNoInteractions(server);
                });
    }

    @ParameterizedTest
    @EnumSource(value = AgentRuntimeState.class, mode = EnumSource.Mode.EXCLUDE, names = "AGENT_READY")
    void readClientRejectsEveryNonReadyStartupState(AgentRuntimeState runtimeState) {
        MBeanServer server = mock(MBeanServer.class);
        readRunner(server, runtimeState)
                .withPropertyValues("platform.tracing.control.channel=NONE")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(TracingConfigurationGuardException.class)
                            .hasMessageContaining("runtimeState=" + runtimeState);
                    verifyNoInteractions(server);
                });
    }

    @Test
    void writableConditionOffDoesNotCreateControlClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformJmxControlAutoConfiguration.class))
                .withPropertyValues("platform.tracing.control.channel=NONE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformJmxTracingSettingsClient.class);
                });
    }

    private static ApplicationContextRunner controlRunner(MBeanServer server, AgentRuntimeState runtimeState) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformJmxControlAutoConfiguration.class))
                .withBean(AgentReadinessCoherenceGuard.class, () -> new AgentReadinessCoherenceGuard(server))
                .withBean(TracingDeploymentDiagnostics.class, () -> diagnostics(runtimeState));
    }

    private static ApplicationContextRunner readRunner(MBeanServer server, AgentRuntimeState runtimeState) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformJmxReadAutoConfiguration.class))
                .withBean(AgentReadinessCoherenceGuard.class, () -> new AgentReadinessCoherenceGuard(server))
                .withBean(TracingDeploymentDiagnostics.class, () -> diagnostics(runtimeState));
    }

    private static TracingDeploymentDiagnostics diagnostics(AgentRuntimeState runtimeState) {
        return TracingDeploymentDiagnostics.withoutAgentData(true, true, runtimeState, "READINESS_NOT_READY");
    }
}
