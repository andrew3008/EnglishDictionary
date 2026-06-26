package space.br1440.platform.datasource.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import space.br1440.platform.datasource.postgres.PostgresDataSourceRoutingAutoConfiguration;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.internal.bootstrap.OnClientDataSourceRoutingCondition;
import space.br1440.platform.datasource.postgres.internal.proxy.core.PasswordExpiryRetryDataSourceProxy;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("PostgresRoutingAutoConfiguration — override points & full-chain back-off")
class PostgresRoutingBackOffTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PostgresDataSourceRoutingAutoConfiguration.class,
                    DataSourceAutoConfiguration.class)
            )
            .withPropertyValues(
                    OnClientDataSourceRoutingCondition.PROPERTY + "=CLIENT",
                    PgClusterProperties.PREFIX + ".nodes[0].host=localhost",
                    PgClusterProperties.PREFIX + ".nodes[0].port=5432",
                    PgClusterProperties.PREFIX + ".database=testdb",
                    PgClusterProperties.PREFIX + ".credentials.username=sa",
                    PgClusterProperties.PREFIX + ".credentials.password=sa",
                    PgClusterProperties.PREFIX + ".pool.write.initialization-fail-timeout=-1",
                    PgClusterProperties.PREFIX + ".pool.read.initialization-fail-timeout=-1");

    @Test
    @DisplayName("default: цепочка Lazy(@Primary) → Routing → PwdRetry(Hikari) создаётся")
    void default_buildsFullProxyChain() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RoutingDataSource.class);
            assertThat(context.containsBean("writeDataSource")).isTrue();
            assertThat(context.containsBean("readDataSource")).isTrue();
            assertThat(context.containsBean("dataSource")).isTrue();

            assertThat(context.getBean("dataSource")).isInstanceOf(LazyConnectionDataSourceProxy.class);
        });
    }

    @Test
    @DisplayName("retry enabled (default): routing-target'ы обёрнуты в PasswordExpiryRetryDataSourceProxy")
    void retryEnabled_wrapsTargetsWithPwdRetry() {
        contextRunner.run(context -> {
            RoutingDataSource routing = context.getBean(RoutingDataSource.class);
            Map<Object, DataSource> targets = routing.getResolvedDataSources();

            assertThat(targets.values())
                    .as("при включённом retry каждый target — PwdRetry(Hikari)")
                    .isNotEmpty()
                    .allMatch(ds -> ds instanceof PasswordExpiryRetryDataSourceProxy);
        });
    }

    @Test
    @DisplayName("retry disabled: routing-target'ы — голые HikariDataSource (без PwdRetry)")
    void retryDisabled_targetsAreBareHikari() {
        contextRunner
                .withPropertyValues(PgClusterProperties.PREFIX + ".retry.invalid-password-retry-enabled=false")
                .run(context -> {
                    RoutingDataSource routing = context.getBean(RoutingDataSource.class);
                    Map<Object, DataSource> targets = routing.getResolvedDataSources();

                    assertThat(targets.values())
                            .as("при выключенном retry target — голый Hikari")
                            .isNotEmpty()
                            .allMatch(ds -> ds instanceof HikariDataSource);
                });
    }

    @Test
    @DisplayName("P0 full-chain back-off: consumer @Bean(name=\"dataSource\") @Primary → НЕТ orphan-пулов/routing")
    void consumerDataSourceOverride_noOrphanPoolsOrRouting() {
        contextRunner
                .withUserConfiguration(ConsumerDataSourceConfig.class)
                .run(context -> {
                    assertThat(context.containsBean("writeDataSource")).isFalse();
                    assertThat(context.containsBean("readDataSource")).isFalse();
                    assertThat(context.getBeansOfType(HikariDataSource.class)).isEmpty();
                    assertThat(context.getBeansOfType(RoutingDataSource.class)).isEmpty();
                    assertThat(context.getBean("dataSource"))
                            .isSameAs(context.getBean(ConsumerDataSourceConfig.class).consumerDataSource);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerDataSourceConfig {
        final DataSource consumerDataSource = mock(DataSource.class);

        @Bean(name = "dataSource")
        @Primary
        DataSource dataSource() {
            return consumerDataSource;
        }
    }
}
