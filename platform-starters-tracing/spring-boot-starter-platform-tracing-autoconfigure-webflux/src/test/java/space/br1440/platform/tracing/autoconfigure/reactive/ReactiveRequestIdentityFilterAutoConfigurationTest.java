package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultInboundTraceControlExtractor;

class ReactiveRequestIdentityFilterAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReactiveTracingAutoConfiguration.class))
            .withBean(TraceOperations.class, () -> NoopTraceOperations.INSTANCE)
            .withBean(TracingProperties.class, TracingProperties::new)
            .withBean(RequestIdentityBoundarySupport.class,
                    () -> new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop()))
            .withBean(InboundTraceControlExtractor.class, DefaultInboundTraceControlExtractor::new);

    @Test
    void requestIdFilterPresentWhenTracingEnabled() {
        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TraceResponseHeaderWebFilter.class);
        });
    }

    @Test
    void requestIdFilterAbsentWhenTracingDisabled() {
        // when
        runner.withPropertyValues("platform.tracing.enabled=false").run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TraceResponseHeaderWebFilter.class);
        });
    }
}
