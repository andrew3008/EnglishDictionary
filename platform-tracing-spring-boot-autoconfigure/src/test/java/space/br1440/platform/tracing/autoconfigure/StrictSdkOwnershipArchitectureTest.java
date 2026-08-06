package space.br1440.platform.tracing.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;
import space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionDescriptor;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Исполнимые ограничения production ownership для Slice E.
 */
class StrictSdkOwnershipArchitectureTest {

    @Test
    void bootstrapSettingsDefaultToEnabledTracing() {
        assertThat(TracingBootstrapSettingsResolver.resolve(new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "HELM")
                .withProperty("platform.environment", "LOCAL")).tracingEnabled()).isTrue();
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
    void applicationDescriptorDoesNotExposeAgentFailureMessage() {
        assertThat(Arrays.stream(AgentExtensionDescriptor.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("failureMessage");
        assertThat(Arrays.stream(AgentExtensionDescriptor.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("failureMessage");
    }
}
