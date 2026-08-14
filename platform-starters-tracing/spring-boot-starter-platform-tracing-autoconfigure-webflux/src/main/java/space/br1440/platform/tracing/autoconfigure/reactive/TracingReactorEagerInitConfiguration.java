package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Hooks;

@AutoConfiguration
@ConditionalOnClass({Hooks.class, LazyInitializationExcludeFilter.class, ReactorAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class TracingReactorEagerInitConfiguration {

    @Bean
    public LazyInitializationExcludeFilter platformTracingEagerReactorAutoConfigurationFilter() {
        return LazyInitializationExcludeFilter.forBeanTypes(ReactorAutoConfiguration.class);
    }

    @Bean
    TracingReactorContextPropagationStartupRunner platformTracingReactorContextPropagationStartupRunner(Environment environment) {
        return new TracingReactorContextPropagationStartupRunner(environment);
    }
}
