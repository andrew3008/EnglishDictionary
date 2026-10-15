package space.br1440.platform.tracing.autoconfigure.control.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusEndpoint;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

class ActuatorEndpointContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TracingStatusAutoConfiguration.class)
            .withBean(TraceOperations.class, () -> NoopTraceOperations.INSTANCE)
            .withBean(TracingRuntime.class, () -> NoOpTracingRuntime.disabledByConfiguration("test"))
            .withBean(TracingBootstrapSettings.class, () -> new TracingBootstrapSettings(
                    false,
                    TracingControlChannel.NONE))
            .withBean(PlatformTracingJmxClient.class,
                    () -> new PlatformTracingJmxClient(new AgentReadinessCoherenceGuard()))
            .withBean(AgentReadinessCoherenceGuard.class, AgentReadinessCoherenceGuard::new)
            .withBean(TracingDeploymentDiagnostics.class, () -> TracingDeploymentDiagnostics.withoutAgentData(
                    false,
                    false,
                    AgentRuntimeState.DISABLED,
                    ""));

    @Test
    void statusEndpointIsReadOnly() {
        // given
        Method[] methods = TracingStatusEndpoint.class.getDeclaredMethods();

        // when
        long writeOperations = Arrays.stream(methods)
                .filter(method -> method.isAnnotationPresent(WriteOperation.class))
                .count();

        // then
        assertThat(writeOperations).isZero();
        assertThat(Arrays.stream(methods).map(Method::getName))
                .containsExactly("tracingStatus");
    }

    @Test
    void controlEndpointExposesSingleWriteOperation() {
        // given
        WebEndpoint endpoint = TracingControlEndpoint.class.getAnnotation(WebEndpoint.class);

        // when
        var writeOperations = Arrays.stream(TracingControlEndpoint.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(WriteOperation.class))
                .toList();

        // then
        assertThat(endpoint.id()).isEqualTo("tracingControl");
        assertThat(writeOperations)
                .singleElement()
                .extracting(Method::getName)
                .isEqualTo("update");
    }

    @Test
    void disabledTracingRegistersReadOnlyStatusEndpoint() {
        // when
        // then
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TracingStatusEndpoint.class);
            assertThat(context.getBean(TracingStatusEndpoint.class).tracingStatus().tracingEnabled())
                    .isFalse();
        });
    }
}
