package space.br1440.platform.datasource.config;

import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
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
import space.br1440.platform.datasource.configuration.condition.OnPgTopologyDirectCondition;
import space.br1440.platform.datasource.health.CqrsRoutingHealthIndicator;
import space.br1440.platform.datasource.metrics.RoutingMetricsCollector;
import space.br1440.platform.datasource.observation.RoutingObservationConvention;
import space.br1440.platform.datasource.observation.RoutingObservationInterceptor;
import space.br1440.platform.datasource.postgres.DataSourceRoutingLifecycle;
import space.br1440.platform.datasource.postgres.NonTransactionalRoutingAspect;
import space.br1440.platform.datasource.postgres.PasswordExpiryRetryDataSourceProxy;
import space.br1440.platform.datasource.postgres.RoutingDataSource;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверки JDBC auto-configuration для нового контракта на основе
 * {@code @ConfigurationProperties}.
 *
 * <h2>Контракт</h2>
 * <p>{@link PostgresClusterDataSourceProperties} — это {@code @ConfigurationProperties}-бин
 * с префиксом {@code platform.datasource.postgres-cluster}. В runtime-сценарии
 * Kubernetes-пода значения подставляются {@code PostgresClusterEnvironmentPostProcessor}-ом
 * из {@code PG_*} environment variables. В этом unit-тесте мы напрямую задаём канонические
 * Spring-свойства через {@link ApplicationContextRunner#withPropertyValues}, поскольку
 * {@code ApplicationContextRunner} <i>не</i> прогоняет фазу {@code ApplicationEnvironmentPreparedEvent}
 * — то есть EPP не вызывается. Это адекватно для проверки auto-configuration: контракт
 * стартера = «увидеть {@code platform.datasource.postgres-cluster.*} ключи и
 * {@code PG_TOPOLOGY=DIRECT}». Полный bootstrap (EPP + canonical mapping) проверяется
 * отдельным E2E-IT-тестом {@code PgTopologyDirectFullCycleE2eIT}.
 */
@DisplayName("JdbcCqrsRoutingAutoConfiguration — auto-configuration через platform.datasource.postgres-cluster.*")
class DataSourceRoutingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JdbcCqrsRoutingAutoConfiguration.class,
                    DataSourceAutoConfiguration.class
            ));

    /** Активирующее property: имитирует env-переменную {@code PG_TOPOLOGY=DIRECT}. */
    private static final String ACTIVATE_PROP =
            OnPgTopologyDirectCondition.ENV_VAR + "=" + OnPgTopologyDirectCondition.DIRECT;

    /**
     * Базовые property для тестов c живым контекстом. Имитируют значения, которые
     * EPP инжектировал бы из {@code PG_*} env-переменных в продакшене.
     */
    private static final String[] VALID_PROPS = {
            ACTIVATE_PROP,
            "platform.datasource.postgres-cluster.nodes=localhost:5432",
            "platform.datasource.postgres-cluster.database=testdb",
            "platform.datasource.postgres-cluster.credentials.username=sa",
            "platform.datasource.postgres-cluster.credentials.password=",
            "platform.datasource.postgres-cluster.hikari.write.connection-timeout=250",
            "platform.datasource.postgres-cluster.hikari.read.connection-timeout=250",
            "platform.datasource.postgres-cluster.hikari.write.initialization-fail-timeout=-1",
            "platform.datasource.postgres-cluster.hikari.read.initialization-fail-timeout=-1"
    };

    @Test
    @DisplayName("PG_TOPOLOGY отсутствует → стартер OFF, бины не создаются")
    void topologyAbsent_noBeansCreated() {
        contextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RoutingDataSource.class);
                    assertThat(context).doesNotHaveBean(NonTransactionalRoutingAspect.class);
                });
    }

    @Test
    @DisplayName("PG_TOPOLOGY=HAPROXY → стартер OFF, бины не создаются")
    void topologyHaproxy_noBeansCreated() {
        contextRunner
                .withPropertyValues(OnPgTopologyDirectCondition.ENV_VAR + "=HAPROXY")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RoutingDataSource.class);
                    assertThat(context).doesNotHaveBean(NonTransactionalRoutingAspect.class);
                });
    }

    @Test
    @DisplayName("PG_TOPOLOGY=<неизвестное> → стартер OFF (защита от опечатки)")
    void topologyUnknown_noBeansCreated() {
        contextRunner
                .withPropertyValues(OnPgTopologyDirectCondition.ENV_VAR + "=GARBAGE")
                .run(context -> assertThat(context).doesNotHaveBean(RoutingDataSource.class));
    }

    @Test
    @DisplayName("PG_TOPOLOGY=direct (lower-case) → стартер ON (case-insensitive по значению)")
    void topologyDirect_lowerCase_createsBeans() {
        String[] propsLowerCase = VALID_PROPS.clone();
        propsLowerCase[0] = OnPgTopologyDirectCondition.ENV_VAR + "=direct";
        contextRunner
                .withPropertyValues(propsLowerCase)
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingDataSource.class);
                    assertThat(context).hasSingleBean(NonTransactionalRoutingAspect.class);
                });
    }

    @Test
    @DisplayName("PG_TOPOLOGY=DIRECT + valid props → создаются routing-бины и PostgresClusterDataSourceProperties")
    void topologyDirect_withValidProps_createsRoutingBeans() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingDataSource.class);
                    assertThat(context).hasSingleBean(NonTransactionalRoutingAspect.class);
                    assertThat(context).hasSingleBean(PostgresClusterDataSourceProperties.class);
                    PostgresClusterDataSourceProperties props =
                            context.getBean(PostgresClusterDataSourceProperties.class);
                    assertThat(props.getNodes()).containsExactly("localhost:5432");
                    assertThat(props.getDatabase()).isEqualTo("testdb");
                    assertThat(props.getCredentials().getUsername()).isEqualTo("sa");
                });
    }

    @Test
    @DisplayName("nodes отсутствует → JSR-303 @NotEmpty не пропускает биндинг")
    void topologyDirect_missingNodes_failsJsr303() {
        contextRunner
                .withPropertyValues(
                        ACTIVATE_PROP,
                        "platform.datasource.postgres-cluster.database=testdb",
                        "platform.datasource.postgres-cluster.credentials.username=sa",
                        "platform.datasource.postgres-cluster.credentials.password=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("nodes");
                });
    }

    @Test
    @DisplayName("database отсутствует → JSR-303 @NotBlank не пропускает биндинг")
    void topologyDirect_missingDatabase_failsJsr303() {
        contextRunner
                .withPropertyValues(
                        ACTIVATE_PROP,
                        "platform.datasource.postgres-cluster.nodes=localhost:5432",
                        "platform.datasource.postgres-cluster.credentials.username=sa",
                        "platform.datasource.postgres-cluster.credentials.password=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("database");
                });
    }

    @Test
    @DisplayName("propertiesDefaults_poolNames")
    void propertiesDefaults_poolNames() {
        HikariRoutingProperties hikari = new HikariRoutingProperties();
        assertThat(hikari.getWrite().getPoolName()).isNull();
        assertThat(hikari.getRead().getPoolName()).isNull();
    }

    @Test
    @DisplayName("PasswordExpiryRetryDataSourceProxy зарегистрирован как @Primary DataSource")
    void enabled_dataSourceIsPasswordRetryProxy() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> {
                    assertThat(context).hasSingleBean(PasswordExpiryRetryDataSourceProxy.class);
                    assertThat(context.getBean(javax.sql.DataSource.class))
                            .isInstanceOf(PasswordExpiryRetryDataSourceProxy.class);
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
                    assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 1);
                });
    }

    @Test
    @DisplayName("Write-пул: connectionTestQuery и keepaliveTime имеют защищённые умолчания")
    void enabled_writePoolHasSafeDefaults() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .run(context -> {
                    HikariDataSource write = (HikariDataSource) context.getBean("writeDataSource");
                    RoutingAssertions.assertThat(write)
                            .isNotReadOnly()
                            .hasConnectionTestQueryContaining("pg_is_in_recovery")
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
                            .hasGauge("datasource.routing.read.strategy", "strategy", "load_balanced");
                });
    }

    @Test
    @DisplayName("Метрики можно отключить через property")
    void enabled_metricsDisabled_noMetricsCollector() {
        contextRunner
                .withPropertyValues(VALID_PROPS)
                .withPropertyValues("platform.datasource.postgres-cluster.metrics.enabled=false")
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
                    assertThat(context).hasSingleBean(CqrsRoutingHealthIndicator.class);
                    assertThat(context.containsBean("routingHealthIndicator")).isTrue();
                });
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
