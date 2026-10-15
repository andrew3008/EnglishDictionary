package space.br1440.platform.tracing.autoconfigure.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

class TracingStatusEndpointRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingStatusAutoConfiguration.class))
            .withBean(TraceOperations.class, () -> NoopTraceOperations.INSTANCE)
            .withBean(TracingRuntime.class, () -> NoOpTracingRuntime.disabledByConfiguration("test"))
            .withBean(TracingBootstrapSettings.class, () -> new TracingBootstrapSettings(
                            true,
                            TracingControlChannel.NONE
                    )
            )
            .withBean(PlatformTracingJmxClient.class,
                    () -> new PlatformTracingJmxClient(new AgentReadinessCoherenceGuard()))
            .withBean(AgentReadinessCoherenceGuard.class, AgentReadinessCoherenceGuard::new)
            .withBean(TracingDeploymentDiagnostics.class, () -> TracingDeploymentDiagnostics.withoutAgentData(
                    true,
                    false,
                    AgentRuntimeState.AGENT_READY,
                    ""))
            .withPropertyValues("platform.tracing.control.channel=NONE");

    @Test
    void statusEndpointIsAbsentWhenActuatorEndpointClassIsHidden() {
        // given
        ApplicationContextRunner runner = contextRunner.withClassLoader(new FilteredClassLoader(Endpoint.class));

        // when
        // then
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TracingStatusEndpoint.class);
        });
    }

    @Test
    void staticChannelStillRegistersOneStatusEndpoint() {
        // given
        // when
        // then
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TracingStatusEndpoint.class);
        });
    }
}
