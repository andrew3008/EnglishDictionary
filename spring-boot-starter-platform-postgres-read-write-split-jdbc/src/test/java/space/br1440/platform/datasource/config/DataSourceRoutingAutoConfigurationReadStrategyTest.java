package space.br1440.platform.datasource.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.datasource.postgres.PostgresDataSourceRoutingAutoConfiguration;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.internal.bootstrap.OnClientDataSourceRoutingCondition;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JdbcDataSourceRoutingAutoConfiguration / read mode")
class DataSourceRoutingAutoConfigurationReadStrategyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PostgresDataSourceRoutingAutoConfiguration.class))
            .withPropertyValues(
                    OnClientDataSourceRoutingCondition.PROPERTY + "=CLIENT",
                    PgClusterProperties.PREFIX + ".nodes[0].host=10.0.0.1",
                    PgClusterProperties.PREFIX + ".nodes[0].port=5432",
                    PgClusterProperties.PREFIX + ".nodes[1].host=10.0.0.2",
                    PgClusterProperties.PREFIX + ".nodes[1].port=5432",
                    PgClusterProperties.PREFIX + ".database=testdb",
                    PgClusterProperties.PREFIX + ".credentials.username=test",
                    PgClusterProperties.PREFIX + ".credentials.password=test",
                    PgClusterProperties.PREFIX + ".pool.write.initialization-fail-timeout=-1",
                    PgClusterProperties.PREFIX + ".pool.read.initialization-fail-timeout=-1"
            );

    @Test
    @DisplayName("default: SECONDARY_PREFERRED → targetServerType=preferSecondary, без loadBalanceHosts")
    void readDataSource_defaultMode_isSecondaryPreferred() {
        contextRunner.run(context -> {
            HikariDataSource read = context.getBean("readDataSource", HikariDataSource.class);

            assertThat(read.getJdbcUrl()).contains("targetServerType=preferSecondary");
            assertThat(read.getJdbcUrl()).doesNotContain("loadBalanceHosts");
            assertThat(read.isReadOnly()).isTrue();
        });
    }

    @Test
    @DisplayName("STRICT_SECONDARY → targetServerType=secondary, без loadBalanceHosts")
    void readDataSource_strictSecondary_usesSecondaryWithoutLoadBalance() {
        contextRunner
                .withPropertyValues(PgClusterProperties.PREFIX + ".read.mode=STRICT_SECONDARY")
                .run(context -> {
                    HikariDataSource read = context.getBean("readDataSource", HikariDataSource.class);

                    assertThat(read.getJdbcUrl()).contains("targetServerType=secondary");
                    assertThat(read.getJdbcUrl()).doesNotContain("preferSecondary");
                    assertThat(read.getJdbcUrl()).doesNotContain("loadBalanceHosts");
                    assertThat(read.isReadOnly()).isTrue();
                });
    }

    @Test
    @DisplayName("ANY_NODE_LOAD_BALANCED → targetServerType=any + loadBalanceHosts=true")
    void readDataSource_anyNodeLoadBalanced_usesAnyWithLoadBalance() {
        contextRunner
                .withPropertyValues(PgClusterProperties.PREFIX + ".read.mode=ANY_NODE_LOAD_BALANCED")
                .run(context -> {
                    HikariDataSource read = context.getBean("readDataSource", HikariDataSource.class);

                    assertThat(read.getJdbcUrl()).contains("targetServerType=any");
                    assertThat(read.getJdbcUrl()).contains("loadBalanceHosts=true");
                    assertThat(read.isReadOnly()).isTrue();
                });
    }

    @Test
    @DisplayName("write-пул не зависит от read-режима: всегда targetServerType=primary, без loadBalanceHosts")
    void writeDataSource_unaffectedByReadMode_keepsPrimaryTargetServerType() {
        contextRunner
                .withPropertyValues(PgClusterProperties.PREFIX + ".read.mode=ANY_NODE_LOAD_BALANCED")
                .run(context -> {
                    HikariDataSource write = context.getBean("writeDataSource", HikariDataSource.class);

                    assertThat(write.getJdbcUrl()).contains("targetServerType=primary");
                    assertThat(write.getJdbcUrl()).doesNotContain("loadBalanceHosts");
                    assertThat(write.isReadOnly()).isFalse();
                });
    }
}
