package space.br1440.platform.datasource.postgres.internal.integration.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = PgClusterProperties.PREFIX + ".metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoutingMetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RoutingMetricsCollector routingMetricsCollector(MeterRegistry registry,
                                                           PgClusterProperties props) {
        return new RoutingMetricsCollector(registry, props.read().mode());
    }
}
