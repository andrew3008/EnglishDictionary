package space.br1440.platform.tracing.autoconfigure.propagation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.propagation.NoOpPlatformContextPropagation;
import space.br1440.platform.tracing.otel.propagation.OtelPlatformContextPropagation;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
public class PlatformContextPropagationAutoConfiguration {

    @Bean
    @ConditionalOnBean(TraceOperations.class)
    @ConditionalOnMissingBean
    public PlatformContextPropagation platformContextPropagation(TraceOperations traceOperations) {
        if (traceOperations instanceof NoopTraceOperations) {
            return NoOpPlatformContextPropagation.INSTANCE;
        }

        return new OtelPlatformContextPropagation();
    }
}
