package space.br1440.platform.datasource.postgres;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.internal.bootstrap.OnClientDataSourceRoutingCondition;
import space.br1440.platform.datasource.postgres.internal.integration.health.RoutingHealthConfiguration;
import space.br1440.platform.datasource.postgres.internal.integration.jooq.RoutingJooqConfiguration;
import space.br1440.platform.datasource.postgres.internal.integration.metrics.RoutingMetricsConfiguration;
import space.br1440.platform.datasource.postgres.internal.integration.observation.RoutingObservationConfiguration;
import space.br1440.platform.datasource.postgres.internal.pool.HikariPoolsConfiguration;
import space.br1440.platform.datasource.postgres.internal.proxy.configuration.DataSourceFactoriesConfiguration;
import space.br1440.platform.datasource.postgres.internal.proxy.configuration.ProxyChainConfiguration;
import space.br1440.platform.datasource.postgres.internal.proxy.configuration.RoutingLifecycleConfiguration;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;

@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@Conditional(OnClientDataSourceRoutingCondition.class)
@ConditionalOnMissingBean(RoutingDataSource.class)
@EnableConfigurationProperties(PgClusterProperties.class)
@Import({
        DataSourceFactoriesConfiguration.class,
        HikariPoolsConfiguration.class,
        ProxyChainConfiguration.class,
        RoutingLifecycleConfiguration.class,
        RoutingJooqConfiguration.class,
        RoutingMetricsConfiguration.class,
        RoutingHealthConfiguration.class,
        RoutingObservationConfiguration.class
})
public class PostgresDataSourceRoutingAutoConfiguration {
}
