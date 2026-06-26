package space.br1440.platform.datasource.it;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.internal.proxy.core.PasswordExpiryRetryDataSourceProxy;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;
import space.br1440.platform.datasource.support.junit.ClusterRoutingItProperties;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.datasource.support.ProductionDataSourceChainSupport.executeSelect1;
import static space.br1440.platform.datasource.support.ProductionDataSourceChainSupport.extractSqlState;

/**
 * Интеграционная верификация password-retry через полный Spring auto-configuration
 * (не ручная сборка {@code ProductionDataSourceChainSupport}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
@DisplayName("Password retry через PostgresRoutingAutoConfiguration (IT)")
class PasswordRetryAutoConfigurationIT {

    private static final String ROT_USER = "retry_autoconfig_user";
    private static final String INITIAL_PWD = "secret1";
    private static final String NEW_PWD = "secret2";

    private PgCluster cluster;
    private ConfigurableApplicationContext context;

    @BeforeAll
    void startContext(PgCluster cluster) throws Exception {
        this.cluster = cluster;
        execPsql("CREATE ROLE " + ROT_USER + " WITH LOGIN PASSWORD '" + INITIAL_PWD + "';");
        execPsql("GRANT ALL PRIVILEGES ON DATABASE " + cluster.dbName() + " TO " + ROT_USER + ";");

        Map<String, Object> props = new HashMap<>(ClusterRoutingItProperties.baseClusterContextProperties(cluster));
        props.put(PgClusterProperties.PREFIX + ".credentials.username", ROT_USER);
        props.put(PgClusterProperties.PREFIX + ".credentials.password", INITIAL_PWD);
        props.put(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size", "2");
        props.put(PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size", "2");
        props.put(PgClusterProperties.PREFIX + ".pool.write.minimum-idle", "0");
        props.put(PgClusterProperties.PREFIX + ".pool.read.minimum-idle", "0");

        SpringApplication app = new SpringApplication(AutoConfigApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(props);
        this.context = app.run();
    }

    @AfterAll
    void stopContext() throws Exception {
        if (context != null && context.isActive()) {
            context.close();
        }
        execPsql("DROP OWNED BY " + ROT_USER + " CASCADE;");
        execPsql("DROP ROLE IF EXISTS " + ROT_USER + ";");
    }

    @Test
    @DisplayName("auto-config: routing-target'ы обёрнуты в PwdRetry и recovery после refresh пароля работает")
    void autoConfig_retryEnabled_recoversAfterExternalPasswordRefresh() throws Exception {
        DataSource primary = context.getBean("dataSource", DataSource.class);
        HikariDataSource writePool = context.getBean("writeDataSource", HikariDataSource.class);
        RoutingDataSource routing = context.getBean(RoutingDataSource.class);

        assertThat(primary).isInstanceOf(LazyConnectionDataSourceProxy.class);
        assertThat(routing.getResolvedDataSources().values())
                .allMatch(ds -> ds instanceof PasswordExpiryRetryDataSourceProxy);

        executeSelect1(primary);

        execPsql("ALTER ROLE " + ROT_USER + " WITH PASSWORD '" + NEW_PWD + "';");
        writePool.getHikariConfigMXBean().setPassword(NEW_PWD);
        writePool.getHikariPoolMXBean().softEvictConnections();
        context.getBean("readDataSource", HikariDataSource.class)
                .getHikariConfigMXBean()
                .setPassword(NEW_PWD);

        executeSelect1(primary);
    }

    @Test
    @DisplayName("auto-config: retry disabled — PwdRetry-обёртки отсутствуют, 28P01 не восстанавливается")
    void autoConfig_retryDisabled_noPwdRetryWrapperAndNoRecovery() throws Exception {
        Map<String, Object> props = new HashMap<>(ClusterRoutingItProperties.baseClusterContextProperties(cluster));
        props.put(PgClusterProperties.PREFIX + ".credentials.username", ROT_USER);
        props.put(PgClusterProperties.PREFIX + ".credentials.password", INITIAL_PWD);
        props.put(PgClusterProperties.PREFIX + ".retry.invalid-password-retry-enabled", "false");
        props.put(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size", "2");
        props.put(PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size", "2");
        props.put(PgClusterProperties.PREFIX + ".pool.write.minimum-idle", "0");
        props.put(PgClusterProperties.PREFIX + ".pool.read.minimum-idle", "0");

        SpringApplication app = new SpringApplication(AutoConfigApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(props);

        try (ConfigurableApplicationContext disabledContext = app.run()) {

            DataSource primary = disabledContext.getBean("dataSource", DataSource.class);
            RoutingDataSource routing = disabledContext.getBean(RoutingDataSource.class);
            HikariDataSource writePool = disabledContext.getBean("writeDataSource", HikariDataSource.class);

            assertThat(routing.getResolvedDataSources().values())
                    .allMatch(ds -> ds instanceof HikariDataSource);

            executeSelect1(primary);

            execPsql("ALTER ROLE " + ROT_USER + " WITH PASSWORD '" + NEW_PWD + "';");
            writePool.getHikariPoolMXBean().softEvictConnections();

            assertThatThrownBy(() -> executeSelect1(primary))
                    .satisfies(ex -> assertThat(extractSqlState(ex)).isEqualToIgnoringCase("28P01"));
        } finally {
            execPsql("ALTER ROLE " + ROT_USER + " WITH PASSWORD '" + INITIAL_PWD + "';");
        }
    }

    private void execPsql(String sql) throws Exception {
        cluster.execPsql(sql);
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    static class AutoConfigApp {
    }
}
