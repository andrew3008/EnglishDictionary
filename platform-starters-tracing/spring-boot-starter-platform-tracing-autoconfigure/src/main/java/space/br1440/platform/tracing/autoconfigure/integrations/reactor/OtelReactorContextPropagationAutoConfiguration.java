package space.br1440.platform.tracing.autoconfigure.integrations.reactor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;

import io.micrometer.context.ContextRegistry;
import io.opentelemetry.context.Context;

@AutoConfiguration
@ConditionalOnClass({ContextRegistry.class, Context.class})
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
public class OtelReactorContextPropagationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OtelReactorContextPropagation platformOtelReactorContextPropagation() {
        return new OtelReactorContextPropagation();
    }
}
