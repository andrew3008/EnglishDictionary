package space.br1440.platform.healthchecks.configuration;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.healthchecks.postgres.PoolHealthEvaluator;
import space.br1440.platform.healthchecks.postgres.PostgresHealthIndicator;
import space.br1440.platform.healthchecks.postgres.PostgresHealthProperties;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(name = "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration")
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(PostgresHealthProperties.class)
public class PostgresHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PoolHealthEvaluator connectionPoolHealthCheck(PostgresHealthProperties properties) {
        return new PoolHealthEvaluator(properties);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(PostgresHealthIndicator.class)
    @ConditionalOnProperty(prefix = "platform.health.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PostgresHealthIndicator postgresHealthIndicatorDelegate(DataSource dataSource,
                                                                   PostgresHealthProperties properties,
                                                                   PoolHealthEvaluator poolHealthEvaluator) {
        return new PostgresHealthIndicator(dataSource, properties, poolHealthEvaluator);
    }

}
