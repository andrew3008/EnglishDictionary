package space.br1440.platform.tracing.autoconfigure.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionDescriptor;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.PlatformEnvironmentPosture;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusEndpoint;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

/**
 * Доказывает отсутствие mutation-capability у единого status endpoint.
 */
class P64ActuatorZeroWriteProofTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TracingStatusAutoConfiguration.class)
            .withBean(TraceOperations.class, () -> NoopTraceOperations.INSTANCE)
            .withBean(TracingRuntime.class, () -> NoOpTracingRuntime.disabledByConfiguration("test"))
            .withBean(TracingBootstrapSettings.class, () -> new TracingBootstrapSettings(
                    false,
                    TracingConfigurationAuthority.HELM,
                    PlatformEnvironmentPosture.LOCAL))
            .withBean(PlatformTracingJmxClient.class, PlatformTracingJmxClient::new)
            .withBean(TracingDeploymentDiagnostics.class, () -> new TracingDeploymentDiagnostics(
                    false,
                    false,
                    AgentRuntimeState.DISABLED,
                    new AgentExtensionDescriptor(
                            AgentRuntimeState.DISABLED,
                            false,
                            false,
                            "",
                            -1,
                            "",
                            "",
                            "",
                            Set.of())));

    @Test
    void tracingStatusHasNoWriteOperation() {
        assertThat(Arrays.stream(TracingStatusEndpoint.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(WriteOperation.class)))
                .isEmpty();
        assertThat(Arrays.stream(TracingStatusEndpoint.class.getDeclaredMethods())
                .map(Method::getName))
                .containsExactly("tracingStatus");
    }

    @Test
    void disabledTracingStillRegistersReadOnlyStatusEndpoint() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TracingStatusEndpoint.class);
            assertThat(context.getBean(TracingStatusEndpoint.class).tracingStatus().tracingEnabled())
                    .isFalse();
        });
    }
}
