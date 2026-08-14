package space.br1440.platform.tracing.autoconfigure.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.PlatformEnvironmentPosture;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingControlEndpoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusEndpoint;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

/**
 * Доказывает разделение read-only статуса и локальной write-поверхности.
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
            .withBean(TracingDeploymentDiagnostics.class, () -> TracingDeploymentDiagnostics.withoutAgentData(
                    false,
                    false,
                    AgentRuntimeState.DISABLED,
                    ""));

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
    void tracingControlIsARealActuatorWebEndpointWithOneWriteOperation() {
        assertThat(TracingControlEndpoint.class.getAnnotation(WebEndpoint.class).id())
                .isEqualTo("tracingControl");
        assertThat(Arrays.stream(TracingControlEndpoint.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(WriteOperation.class)))
                .singleElement()
                .extracting(Method::getName)
                .isEqualTo("update");
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
