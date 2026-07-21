package space.br1440.platform.tracing.core.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;
import space.br1440.platform.tracing.api.span.builder.ActiveTraceContextView;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

class IdentityPublicSurfaceTest {

    @Test
    void approvedIdentityApiIsExact() throws Exception {
        assertThat(CorrelationScope.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactly("close");
        assertThat(TraceOperations.class.getDeclaredMethods()).hasSize(5);
        assertThat(TraceOperations.class.getMethod("openCorrelationScope", String.class).getReturnType())
                .isEqualTo(CorrelationScope.class);
        assertThat(TraceOperations.class.getMethod("withCorrelationId", String.class, Runnable.class)
                .getReturnType()).isEqualTo(void.class);
        assertThat(TraceOperations.class.getMethod("withCorrelationId", String.class, ThrowingSupplier.class)
                .getExceptionTypes()).containsExactly(Exception.class);
        assertThat(ActiveTraceContextView.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("traceId", "spanId", "requestId", "correlationId");
        assertThat(Arrays.stream(RequestTraceContextSnapshot.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .containsExactly("requestId", "correlationId", "traceId", "spanId");
    }

    @Test
    void infrastructureIdentityTypesDoNotEnterPlatformApi() {
        for (String simpleName : new String[]{
                "RequestContextAccessor",
                "RequestIdentityAccessor",
                "RequestIdentityContext",
                "RequestIdentityBinder",
                "RequestIdentityScope"
        }) {
            assertThatThrownBy(() -> Class.forName("space.br1440.platform.tracing.api." + simpleName))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }
}
