package space.br1440.platform.datasource.postgres.internal.proxy.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.internal.integration.metrics.RoutingMetricsCollector;
import space.br1440.platform.datasource.postgres.internal.integration.observation.RoutingObservationInterceptor;
import space.br1440.platform.datasource.postgres.internal.proxy.core.PasswordExpiryRetryDataSourceProxy;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingBean(name = "dataSource")
public class ProxyChainConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(name = "dataSource")
    public static class RoutingLayerConfiguration {

        @Bean
        @ConditionalOnMissingBean(RoutingDataSource.class)
        public RoutingDataSource routingDataSource(@Qualifier("writeDataSource") HikariDataSource writeDataSource,
                                                   @Qualifier("readDataSource") HikariDataSource readDataSource,
                                                   PgClusterProperties properties,
                                                   ObjectProvider<RoutingMetricsCollector> metricsProvider,
                                                   ObjectProvider<RoutingObservationInterceptor> observationProvider) {
            boolean retryEnabled = properties.retry().invalidPasswordRetryEnabled();
            return new RoutingDataSource(
                    wrapWithPasswordRetry(writeDataSource, retryEnabled),
                    wrapWithPasswordRetry(readDataSource, retryEnabled),
                    metricsProvider.getIfAvailable(),
                    observationProvider.getIfAvailable()
            );
        }

        private static DataSource wrapWithPasswordRetry(HikariDataSource pool, boolean retryEnabled) {
            if (!retryEnabled) {
                return pool;
            }

            return new PasswordExpiryRetryDataSourceProxy(
                    pool,
                    () -> pool.getHikariPoolMXBean().softEvictConnections()
            );
        }
    }

    @Bean(name = "dataSource")
    @Primary
    @ConditionalOnMissingBean(name = "dataSource")
    public LazyConnectionDataSourceProxy dataSource(RoutingDataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
