package space.br1440.platform.datasource.postgres.internal.integration.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
@EnableConfigurationProperties(RoutingHealthProperties.class)
public class RoutingHealthConfiguration {

    @Bean("routingHealthIndicator")
    @ConditionalOnBean(name = {"writeDataSource", "readDataSource"})
    @ConditionalOnMissingBean(name = "routingHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("routing")
    @DependsOn({"writeDataSource", "readDataSource"})
    public DataSourceRoutingHealthIndicator routingHealthIndicator(@Qualifier("writeDataSource") DataSource writeDataSource,
                                                                   @Qualifier("readDataSource") DataSource readDataSource,
                                                                   PgClusterProperties properties,
                                                                   RoutingHealthProperties healthProperties) {
        return new DataSourceRoutingHealthIndicator(
                writeDataSource,
                readDataSource,
                properties.read().mode(),
                healthProperties);
    }
}
