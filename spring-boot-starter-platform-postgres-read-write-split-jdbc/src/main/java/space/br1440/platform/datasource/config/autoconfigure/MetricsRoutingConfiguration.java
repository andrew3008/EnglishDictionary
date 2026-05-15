package space.br1440.platform.datasource.config.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.metrics.RoutingMetricsCollector;

/**
 * Регистрирует {@link RoutingMetricsCollector}, если в контексте присутствует
 * {@link MeterRegistry} и метрики не отключены свойством
 * {@code platform.datasource.postgres-cluster.metrics.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "platform.datasource.postgres-cluster.metrics",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class MetricsRoutingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RoutingMetricsCollector routingMetricsCollector(MeterRegistry registry,
                                                           PostgresClusterDataSourceProperties props) {
        return new RoutingMetricsCollector(registry, props.getReadReplica().getStrategy());
    }
}
