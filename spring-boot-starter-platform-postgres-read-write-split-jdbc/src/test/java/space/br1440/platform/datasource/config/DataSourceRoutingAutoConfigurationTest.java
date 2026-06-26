package space.br1440.platform.datasource.config;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.postgres.PostgresDataSourceRoutingAutoConfiguration;
import space.br1440.platform.datasource.postgres.internal.bootstrap.OnClientDataSourceRoutingCondition;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PoolGroupProperties;
import space.br1440.platform.datasource.postgres.internal.pool.PgClusterPropertiesValidator;
import space.br1440.platform.datasource.postgres.internal.integration.health.DataSourceRoutingHealthIndicator;
import space.br1440.platform.datasource.postgres.internal.integration.metrics.RoutingMetricsCollector;
import space.br1440.platform.datasource.postgres.internal.integration.observation.RoutingObservationConvention;
import space.br1440.platform.datasource.postgres.internal.integration.observation.RoutingObservationInterceptor;
import space.br1440.platform.datasource.postgres.internal.proxy.lifecycle.DataSourceRoutingLifecycle;
import space.br1440.platform.datasource.postgres.internal.proxy.aop.NonTransactionalRoutingAspect;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;

import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.StatusAggregator;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JdbcDataSourceRoutingAutoConfiguration — auto-configuration через " + PgClusterProperties.PREFIX + ".*")
class DataSourceRoutingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PostgresDataSourceRoutingAutoConfiguration.class,
                    DataSourceAutoConfiguration.class
            ));

    private static final String ACTIVATE_PROP = OnClientDataSourceRoutingCondition.PROPERTY + "=CLIENT";

    private static final String[] VALID_PROPS = {
            ACTIVATE_PROP,
            PgClusterProperties.PREFIX + ".nodes[0].host=localhost",
            PgClusterProperties.PREFIX + ".nodes[0].port=5432",
            PgClusterProperties.PREFIX + ".database=testdb",
            PgClusterProperties.PREFIX + ".credentials.username=sa",
            PgClusterProperties.PREFIX + ".credentials.password=sa",
            PgClusterProperties.PREFIX + ".pool.write.connection-timeout=250",
            PgClusterProperties.PREFIX + ".pool.read.connection-timeout=250",
            PgClusterProperties.PREFIX + ".pool.write.initialization-fail-timeout=-1",
            PgClusterProperties.PREFIX + ".pool.read.initialization-fail-timeout=-1"
    };

    @Test
    @DisplayName("datasource-routing-mode отсутствует → стартер OFF, бины не создаются")
    void routingModeAbsent_noBeansCreated() {
        contextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RoutingDataSource.class);
                    assertThat(context).doesNotHaveBean(NonTransactionalRoutingAspect.class);
                });
    }

    @Test
    @DisplayName("datasource-routing-mode=PROXY → стартер OFF, бины не создаются")
    void routingModeProxy_noBeansCreated() {
        contextRunner
                .withPropertyValues(OnClientDataSourceRoutingCondition.PROPERTY + "=PROXY")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RoutingDataSource.class);
                    assertThat(context).doesNotHaveBean(NonTransactionalRoutingAspect.class);
                });
    }

    @Test
    @DisplayName("datasource-routing-mode=<неизвестное> → стартер OFF (защита от опечатки)")
    void routingModeUnknown_noBeansCreated() {
        contextRunner
                .withPropertyValues(OnClientDataSourceRoutingCondition.PROPERTY + "=GARBAGE")
                .run(context -> assertThat(context).doesNotHaveBean(RoutingDataSource.class));
    }

    @Test
    @DisplayName("datasource-routing-mode=client (lower-case) → стартер ON (case-insensitive по значению)")
    void routingModeClient_lowerCase_createsBeans() {
        String[] propsLowerCase = VALID_PROPS.clone();
        propsLowerCase[0] = OnClientDataSourceRoutingCondition.PROPERTY + "=client";
        contextRunner
                .withPropertyValues(propsLowerCase)
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingDataSource.class);
                    assertThat(context).hasSingleBean(NonTransactionalRoutingAspect.class);
                });
    }

    @Test
    @DisplayName("CLIENT + valid props → создаются routing-бины и PgClusterProperties")
    void routingModeClient_withValidProps_createsRoutingBeans() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingDataSource.class);
                    assertThat(context).hasSingleBean(NonTransactionalRoutingAspect.class);
                    assertThat(context).hasSingleBean(PgClusterProperties.class);
                    PgClusterProperties props = context.getBean(PgClusterProperties.class);
                    assertThat(props.nodes()).hasSize(1);
                    assertThat(props.nodes().getFirst().toHostPort()).isEqualTo("localhost:5432");
                    assertThat(props.database()).isEqualTo("testdb");
                    assertThat(props.credentials().username()).isEqualTo("sa");
                });
    }

    @Test
    @DisplayName("PgClusterPropertiesValidator зарегистрирован как бин")
    void enabled_validatorBeanRegistered() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> assertThat(context).hasSingleBean(PgClusterPropertiesValidator.class));
    }

    @Test
    @DisplayName("propertiesDefaults_poolNames — по умолчанию poolName не задан (резолвится policy-слоем)")
    void propertiesDefaults_poolNames() {
        PoolGroupProperties pool = new PoolGroupProperties(null, null);
        assertThat(pool.write().poolName()).isNull();
        assertThat(pool.read().poolName()).isNull();
    }

    @Test
    @DisplayName("@Primary DataSource — это LazyConnectionDataSourceProxy (Lazy outermost, PR 4.1)")
    void enabled_dataSourceIsLazyProxy() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> {
                    assertThat(context.getBean("dataSource"))
                            .isInstanceOf(org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy.class);
                    assertThat(context.getBean(javax.sql.DataSource.class))
                            .isInstanceOf(org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy.class);
                });
    }

    @Test
    @DisplayName("DataSourceRoutingLifecycle создан с правильной shutdown-фазой")
    void enabled_lifecycleHasCorrectPhase() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSourceRoutingLifecycle.class);
                    DataSourceRoutingLifecycle lifecycle = context.getBean(DataSourceRoutingLifecycle.class);

                    assertThat(lifecycle.getPhase())
                            .isEqualTo(org.springframework.context.SmartLifecycle.DEFAULT_PHASE - 2048);
                });
    }

    @Test
    @DisplayName("Write-пул: connectionInitSql (primary), connectionTestQuery=SELECT 1 и keepaliveTime")
    void enabled_writePoolHasSafeDefaults() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> {
                    HikariDataSource write = (HikariDataSource) context.getBean("writeDataSource");
                    RoutingAssertions.assertThat(write)
                            .isNotReadOnly()
                            .hasConnectionInitSqlContaining("pg_is_in_recovery")
                            .hasConnectionTestQueryContaining("SELECT 1")
                            .hasKeepaliveTime(60_000L);
                });
    }

    @Test
    @DisplayName("Read-пул помечен readOnly=true")
    void enabled_readPoolIsReadOnly() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> {
                    HikariDataSource read = (HikariDataSource) context.getBean("readDataSource");
                    RoutingAssertions.assertThat(read).isReadOnly();
                });
    }

    @Test
    @DisplayName("Без MeterRegistry в контексте: RoutingMetricsCollector НЕ создаётся")
    void enabled_withoutMeterRegistry_noMetricsCollector() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> assertThat(context).doesNotHaveBean(RoutingMetricsCollector.class));
    }

    @Test
    @DisplayName("С MeterRegistry в контексте: RoutingMetricsCollector создаётся")
    void enabled_withMeterRegistry_createsMetricsCollector() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .withUserConfiguration(MeterRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingMetricsCollector.class);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    RoutingAssertions.assertThat(registry)
                            .hasGauge("datasource.routing.read.strategy",
                                    "db.routing.read.strategy", "secondary_preferred");
                });
    }

    @Test
    @DisplayName("Метрики можно отключить через property")
    void enabled_metricsDisabled_noMetricsCollector() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .withPropertyValues(PgClusterProperties.PREFIX + ".metrics.enabled=false")
                .withUserConfiguration(MeterRegistryConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean(RoutingMetricsCollector.class));
    }

    @Test
    @DisplayName("С ObservationRegistry в контексте: RoutingObservationInterceptor создаётся")
    void enabled_withObservationRegistry_createsInterceptor() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingObservationInterceptor.class);
                    assertThat(context).hasSingleBean(RoutingObservationConvention.class);
                });
    }

    @Test
    @DisplayName("HealthIndicator (ключ Actuator 'routing') создаётся, когда Actuator HealthIndicator на classpath")
    void enabled_createsHealthIndicator() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSourceRoutingHealthIndicator.class);
                    assertThat(context.containsBean("routingHealthIndicator")).isTrue();
                });
    }

    @Test
    @DisplayName("Глобальный StatusAggregator не регистрируется автоматически")
    void enabled_noGlobalStatusAggregator() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> assertThat(context).doesNotHaveBean(StatusAggregator.class));
    }

    @Test
    @DisplayName("Глобальный HttpCodeStatusMapper не регистрируется автоматически")
    void enabled_noGlobalHttpCodeStatusMapper() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> assertThat(context).doesNotHaveBean(HttpCodeStatusMapper.class));
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class ObservationRegistryConfig {
        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }
}
