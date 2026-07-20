package space.br1440.platform.tracing.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.support.AgentExtensionDescriptor;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Исполнимые ограничения production ownership для Slice E.
 */
class StrictSdkOwnershipArchitectureTest {

    @Test
    void productionModeSurfaceContainsOnlyAgentAndDisabled() {
        assertThat(Arrays.stream(SdkMode.values()).map(Enum::name))
                .containsExactly("AGENT", "DISABLED");
        assertThat(new TracingProperties().getSdk().getMode()).isEqualTo(SdkMode.AGENT);
    }

    @Test
    void compositionRootDoesNotAcceptApplicationOpenTelemetry() {
        assertThat(Arrays.stream(TracingCoreAutoConfiguration.class.getDeclaredMethods())
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
