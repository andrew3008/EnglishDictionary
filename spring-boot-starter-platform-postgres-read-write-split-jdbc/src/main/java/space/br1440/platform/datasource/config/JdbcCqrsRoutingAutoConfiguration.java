package space.br1440.platform.datasource.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import space.br1440.platform.datasource.config.autoconfigure.HealthRoutingConfiguration;
import space.br1440.platform.datasource.config.autoconfigure.JooqRoutingConfiguration;
import space.br1440.platform.datasource.config.autoconfigure.MetricsRoutingConfiguration;
import space.br1440.platform.datasource.config.autoconfigure.ObservationRoutingConfiguration;
import space.br1440.platform.datasource.config.factory.ReadDataSourceFactory;
import space.br1440.platform.datasource.config.factory.WriteDataSourceFactory;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.condition.OnPgTopologyDirectCondition;
import space.br1440.platform.datasource.configuration.support.ReadStrategyStartupLogger;
import space.br1440.platform.datasource.configuration.support.RoutingStartupValidator;
import space.br1440.platform.datasource.metrics.RoutingMetricsCollector;
import space.br1440.platform.datasource.observation.RoutingObservationInterceptor;
import space.br1440.platform.datasource.postgres.DataSourceRoutingLifecycle;
import space.br1440.platform.datasource.postgres.NonTransactionalRoutingAspect;
import space.br1440.platform.datasource.postgres.PasswordExpiryRetryDataSourceProxy;
import space.br1440.platform.datasource.postgres.RoutingDataSource;

/**
 * Авто-конфигурация библиотеки Platform DataSource Routing — JDBC-стартер (HikariCP).
 *
 * <p>Создаёт два статических HikariCP-пула в словаре CQRS-intent:
 * <ul>
 *   <li>{@code writeDataSource} — write-пул, всегда на Primary pg-ноду</li>
 *   <li>{@code readDataSource} — read-пул, физическое заполнение зависит от
 *       {@link PostgresClusterDataSourceProperties.ReadReplica.Strategy} (SECONDARY_ONLY или LOAD_BALANCED)</li>
 * </ul>
 * Пулы используют pgJDBC multi-host URL с {@code targetServerType} для выбора физических нод.
 *
 * <h2>Источник конфигурации</h2>
 * <p>{@link PostgresClusterDataSourceProperties} — это {@code @ConfigurationProperties}-бин
 * с префиксом {@code platform.datasource.postgres-cluster.*}. Дефолтные значения для всех
 * полей инжектируются {@code PostgresClusterEnvironmentPostProcessor}-ом из {@code PG_*}
 * environment variables (Kubernetes ConfigMap/Secret) с самым низким приоритетом —
 * это даёт zero-config контракт: разработчику не нужно писать ни одной строки
 * {@code platform.datasource.postgres-cluster.*} в собственном {@code application.yml}.
 * При желании переопределить любое поле (например, {@code read-replica.strategy=SECONDARY_ONLY})
 * — стандартный Spring Boot relaxed-binding позволяет это сделать в собственном yml.
 *
 * <p>Стартер активируется только при {@code PG_TOPOLOGY=DIRECT}
 * ({@link OnPgTopologyDirectCondition} — single activation gate).
 * В режимах {@code PG_TOPOLOGY=HAPROXY} или при отсутствии переменной condition
 * не срабатывает, и микросервис идёт по канонической Spring Boot
 * {@link DataSourceAutoConfiguration} через {@code spring.datasource.url=${POSTGRES_URL}}.
 *
 * <p>Конфигурация HikariCP-пулов (write/read sizes, timeouts) биндится
 * через {@link HikariRoutingProperties} с префиксом
 * {@code platform.datasource.postgres-cluster.hikari.*}.
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@Conditional(OnPgTopologyDirectCondition.class)
@ConditionalOnMissingBean(RoutingDataSource.class)
@EnableConfigurationProperties({PostgresClusterDataSourceProperties.class, HikariRoutingProperties.class})
@Import({
        JooqRoutingConfiguration.class,
        MetricsRoutingConfiguration.class,
        HealthRoutingConfiguration.class,
        ObservationRoutingConfiguration.class
})
public class JdbcCqrsRoutingAutoConfiguration {

    @Bean
    public RoutingStartupValidator routingStartupValidator() {
        return new RoutingStartupValidator();
    }

    @Bean
    public ReadStrategyStartupLogger readStrategyStartupLogger() {
        return new ReadStrategyStartupLogger();
    }

    @Bean
    public WriteDataSourceFactory writeDataSourceFactory(RoutingStartupValidator validator,
                                                        ReadStrategyStartupLogger logger) {
        return new WriteDataSourceFactory(validator, logger);
    }

    @Bean
    public ReadDataSourceFactory readDataSourceFactory() {
        return new ReadDataSourceFactory();
    }

    @Bean
    @ConditionalOnMissingBean(name = "writeDataSource")
    public HikariDataSource writeDataSource(WriteDataSourceFactory factory,
                                            PostgresClusterDataSourceProperties props,
                                            HikariRoutingProperties hikari) {
        return factory.build(props, hikari);
    }

    @Bean
    @ConditionalOnMissingBean(name = "readDataSource")
    public HikariDataSource readDataSource(ReadDataSourceFactory factory,
                                           PostgresClusterDataSourceProperties props,
                                           HikariRoutingProperties hikari) {
        return factory.build(props, hikari);
    }

    @Bean
    public RoutingDataSource routingDataSource(@Qualifier("writeDataSource") HikariDataSource writeDataSource,
                                               @Qualifier("readDataSource") HikariDataSource readDataSource,
                                               ObjectProvider<RoutingMetricsCollector> metricsProvider,
                                               ObjectProvider<RoutingObservationInterceptor> observationProvider) {
        return new RoutingDataSource(
                writeDataSource,
                readDataSource,
                metricsProvider.getIfAvailable(),
                observationProvider.getIfAvailable()
        );
    }

    @Bean
    public LazyConnectionDataSourceProxy lazyConnectionDataSource(RoutingDataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }

    @Bean
    @Primary
    public PasswordExpiryRetryDataSourceProxy dataSource(LazyConnectionDataSourceProxy lazyConnectionDataSource,
                                                         @Qualifier("writeDataSource") HikariDataSource writeDataSource,
                                                         @Qualifier("readDataSource") HikariDataSource readDataSource) {
        return new PasswordExpiryRetryDataSourceProxy(lazyConnectionDataSource, () -> {
            writeDataSource.getHikariPoolMXBean().softEvictConnections();
            readDataSource.getHikariPoolMXBean().softEvictConnections();
        });
    }

    @Bean
    @ConditionalOnMissingBean(DataSourceRoutingLifecycle.class)
    public DataSourceRoutingLifecycle dataSourceRoutingLifecycle(@Qualifier("writeDataSource") HikariDataSource writeDataSource,
                                                                 @Qualifier("readDataSource") HikariDataSource readDataSource) {
        return new DataSourceRoutingLifecycle(writeDataSource, readDataSource);
    }

    @Bean
    @ConditionalOnMissingBean(NonTransactionalRoutingAspect.class)
    public NonTransactionalRoutingAspect nonTransactionalRoutingAspect() {
        return new NonTransactionalRoutingAspect();
    }

    @Bean
    @ConditionalOnMissingBean(RoutingAnnotationConflictValidator.class)
    public RoutingAnnotationConflictValidator routingAnnotationConflictValidator(ListableBeanFactory beanFactory) {
        return new RoutingAnnotationConflictValidator(beanFactory);
    }
}
