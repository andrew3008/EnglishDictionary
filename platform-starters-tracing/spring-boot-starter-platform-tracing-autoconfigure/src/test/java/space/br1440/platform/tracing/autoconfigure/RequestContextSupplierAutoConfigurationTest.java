package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;
import space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestTraceContextSnapshotSupplier;

class RequestContextSupplierAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestContextSupplierAutoConfiguration.class));

    @Test
    void processedAfterTracingRuntimeAutoConfiguration() {
        // given
        assertThat(RequestContextSupplierAutoConfiguration.class.getAnnotation(AutoConfiguration.class).after())
                .contains(TracingRuntimeAutoConfiguration.class);
    }

    @Test
    void supplier_registers_by_default() {
        runner.run(context -> {
            assertThat(context).hasBean("platformRequestTraceContextSnapshotSupplier");
            assertThat(context.getBean("platformRequestTraceContextSnapshotSupplier"))
                    .isInstanceOf(RequestTraceContextSnapshotSupplier.class);
        });
    }

    @Test
    void supplier_registers_when_tracing_disabled() {
        runner.withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("platformRequestTraceContextSnapshotSupplier");

                    @SuppressWarnings("unchecked")
                    Supplier<RequestTraceContextSnapshot> supplier =
                            (Supplier<RequestTraceContextSnapshot>) context.getBean("platformRequestTraceContextSnapshotSupplier");

                    RequestTraceContextSnapshot ctx = supplier.get();
                    assertThat(ctx).isNotNull();
                    assertThat(ctx.traceId()).isNull();
                    assertThat(ctx.spanId()).isNull();
                });
    }

    @Test
    void custom_bean_replaces_default() {
        runner.withUserConfiguration(CustomSupplierConfig.class)
                .run(context -> {
                    assertThat(context).hasBean("platformRequestTraceContextSnapshotSupplier");
                    @SuppressWarnings("unchecked")
                    Supplier<RequestTraceContextSnapshot> supplier =
                            (Supplier<RequestTraceContextSnapshot>) context.getBean("platformRequestTraceContextSnapshotSupplier");
                    RequestTraceContextSnapshot ctx = supplier.get();
                    assertThat(ctx.correlationId()).isEqualTo("custom-correlation");
                });
    }

    static class CustomSupplierConfig {

        @Bean(name = "platformRequestTraceContextSnapshotSupplier")
        public Supplier<RequestTraceContextSnapshot> platformRequestTraceContextSnapshotSupplier() {
            return () -> new RequestTraceContextSnapshot(null, "custom-correlation", null, null);
        }
    }
}
