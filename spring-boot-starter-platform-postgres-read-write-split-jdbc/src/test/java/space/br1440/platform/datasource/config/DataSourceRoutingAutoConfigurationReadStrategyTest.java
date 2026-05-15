package space.br1440.platform.datasource.config;

import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.datasource.configuration.condition.OnPgTopologyDirectCondition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring-контекстные тесты конфигурации read-пула для разных режимов
 * {@link PostgresClusterDataSourceProperties.ReadReplica.Strategy}.
 *
 * <p>Подключение к реальной БД не выполняется ({@code initialization-fail-timeout=-1}),
 * проверяется только результирующий JDBC URL и {@code readOnly}-флаг {@link HikariDataSource}.
 *
 * <h2>Контракт смены стратегии</h2>
 * <p>{@link PostgresClusterDataSourceProperties} — это {@code @ConfigurationProperties}-бин,
 * стратегия чтения управляется свойством
 * {@code platform.datasource.postgres-cluster.read-replica.strategy} с дефолтом
 * {@link PostgresClusterDataSourceProperties.ReadReplica.Strategy#LOAD_BALANCED}
 * (read-heavy сервисы по умолчанию). Для перехода на
 * {@link PostgresClusterDataSourceProperties.ReadReplica.Strategy#SECONDARY_ONLY}
 * потребитель просто прописывает property в собственном {@code application.yml}
 * — programmatic {@code @Bean} override (escape-hatch) больше не требуется.
 */
@DisplayName("JdbcCqrsRoutingAutoConfiguration / read-replica strategy")
class DataSourceRoutingAutoConfigurationReadStrategyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcCqrsRoutingAutoConfiguration.class))
            .withPropertyValues(
                    OnPgTopologyDirectCondition.ENV_VAR + "=" + OnPgTopologyDirectCondition.DIRECT,
                    "platform.datasource.postgres-cluster.nodes=10.0.0.1:5432,10.0.0.2:5432",
                    "platform.datasource.postgres-cluster.database=testdb",
                    "platform.datasource.postgres-cluster.credentials.username=test",
                    "platform.datasource.postgres-cluster.credentials.password=test",
                    "platform.datasource.postgres-cluster.hikari.write.initialization-fail-timeout=-1",
                    "platform.datasource.postgres-cluster.hikari.read.initialization-fail-timeout=-1"
            );

    @Test
    @DisplayName("default: LOAD_BALANCED → targetServerType=any + loadBalanceHosts=true")
    void readDataSource_defaultStrategy_isLoadBalanced() {
        contextRunner.run(context -> {
            HikariDataSource read = context.getBean("readDataSource", HikariDataSource.class);

            assertThat(read.getJdbcUrl()).contains("targetServerType=any");
            assertThat(read.getJdbcUrl()).contains("loadBalanceHosts=true");
            assertThat(read.isReadOnly())
                    .as("Read pool must remain readOnly=true even with LOAD_BALANCED strategy")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("property override: SECONDARY_ONLY → targetServerType=preferSecondary, без loadBalanceHosts")
    void readDataSource_secondaryOnlyOverride_usesPreferSecondaryWithoutLoadBalance() {
        contextRunner
                .withPropertyValues("platform.datasource.postgres-cluster.read-replica.strategy=SECONDARY_ONLY")
                .run(context -> {
                    HikariDataSource read = context.getBean("readDataSource", HikariDataSource.class);

                    assertThat(read.getJdbcUrl()).contains("targetServerType=preferSecondary");
                    assertThat(read.getJdbcUrl()).doesNotContain("loadBalanceHosts");
                    assertThat(read.getJdbcUrl()).doesNotContain("targetServerType=any");
                    assertThat(read.isReadOnly()).isTrue();
                });
    }

    @Test
    @DisplayName("write-пул не зависит от read-стратегии: всегда targetServerType=primary, без loadBalanceHosts")
    void writeDataSource_unaffectedByReadStrategy_keepsPrimaryTargetServerType() {
        contextRunner
                .withPropertyValues("platform.datasource.postgres-cluster.read-replica.strategy=SECONDARY_ONLY")
                .run(context -> {
                    HikariDataSource write = context.getBean("writeDataSource", HikariDataSource.class);

                    assertThat(write.getJdbcUrl()).contains("targetServerType=primary");
                    assertThat(write.getJdbcUrl()).doesNotContain("loadBalanceHosts");
                    assertThat(write.isReadOnly()).isFalse();
                });
    }
}
