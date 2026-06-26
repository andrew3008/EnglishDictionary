package space.br1440.platform.datasource.postgres.internal.integration.observation;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.micrometer.observation.ObservationRegistry")
@ConditionalOnBean(ObservationRegistry.class)
public class RoutingObservationConfiguration {

    @Bean
    @ConditionalOnMissingBean(RoutingObservationConvention.class)
    public RoutingObservationConvention routingObservationConvention() {
        return new RoutingObservationConvention.Default();
    }

    @Bean
    @ConditionalOnMissingBean(RoutingObservationInterceptor.class)
    public RoutingObservationInterceptor routingObservationInterceptor(ObservationRegistry registry,
                                                                       RoutingObservationConvention convention) {
        return new RoutingObservationInterceptor(registry, convention);
    }
}
