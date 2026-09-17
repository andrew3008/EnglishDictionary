package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;

import io.opentelemetry.api.OpenTelemetry;

class StrictSdkOwnershipContractTest {

    @Test
    void bootstrapSettingsDefaultToEnabledTracing() {
        assertThat(TracingBootstrapSettingsResolver.resolve(new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "STATIC")
                ).tracingEnabled()).isTrue();
    }

    @Test
    void compositionRootDoesNotAcceptApplicationOpenTelemetry() {
        assertThat(Arrays.stream(TracingRuntimeAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .filter(OpenTelemetry.class::equals))
                .isEmpty();
    }

    @Test
    void applicationDiagnosticsDoesNotExposeAgentFailureMessage() {
        assertThat(Arrays.stream(TracingDeploymentDiagnostics.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("failureMessage");
        assertThat(Arrays.stream(TracingDeploymentDiagnostics.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("failureMessage");
        assertThat(Arrays.stream(TracingDeploymentDiagnostics.AgentReadiness.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("failureMessage");
        assertThat(Arrays.stream(TracingDeploymentDiagnostics.AgentReadiness.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("failureMessage");
    }
}
