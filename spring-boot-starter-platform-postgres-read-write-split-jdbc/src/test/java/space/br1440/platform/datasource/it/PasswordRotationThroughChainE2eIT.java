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
import space.br1440.platform.datasource.support.junit.ClusterRoutingItProperties;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.datasource.support.ProductionDataSourceChainSupport.executeSelect1;

/**
 * E7 — password rotation through full auto-configured prod chain ({@code @Primary dataSource}).
 *
 * <p>Проверяет PR 4.1 на уровне Spring-контекста: {@code @Primary dataSource} — это
 * {@link LazyConnectionDataSourceProxy} (Lazy outermost), а после внешнего обновления
 * Hikari-пароля (симуляция ESO/Vault Agent) prod-цепочка восстанавливается через PwdRetry
 * на routing-target'ах.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
@DisplayName("E7: password rotation through @Primary dataSource (full Spring chain)")
class PasswordRotationThroughChainE2eIT {

    private static final String ROT_USER = "rotuser_e7";
    private static final String INITIAL_PWD = "secret1";
    private static final String NEW_PWD = "secret2";

    private PgCluster cluster;
    private ConfigurableApplicationContext context;

    @BeforeAll
    void startContext(PgCluster cluster) throws Exception {
        this.cluster = cluster;
        execPsql("CREATE ROLE " + ROT_USER + " WITH LOGIN PASSWORD '" + INITIAL_PWD + "';");
        execPsql("GRANT ALL PRIVILEGES ON DATABASE " + cluster.dbName() + " TO " + ROT_USER + ";");

        Map<String, Object> defaultProperties = new HashMap<>(ClusterRoutingItProperties.baseClusterContextProperties(cluster));
        defaultProperties.put(PgClusterProperties.PREFIX + ".credentials.username", ROT_USER);
        defaultProperties.put(PgClusterProperties.PREFIX + ".credentials.password", INITIAL_PWD);
        defaultProperties.put(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size", "2");
        defaultProperties.put(PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size", "2");
        defaultProperties.put(PgClusterProperties.PREFIX + ".pool.write.minimum-idle", "0");
        defaultProperties.put(PgClusterProperties.PREFIX + ".pool.read.minimum-idle", "0");

        SpringApplication app = new SpringApplication(E7App.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(defaultProperties);
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
    @DisplayName("E7: @Primary dataSource — это Lazy; ротация + внешний refresh восстанавливает соединение")
    void passwordRotationThroughChain_withRefresh_recovers() throws Exception {
        DataSource primary = context.getBean("dataSource", DataSource.class);
        HikariDataSource writePool = context.getBean("writeDataSource", HikariDataSource.class);
        HikariDataSource readPool = context.getBean("readDataSource", HikariDataSource.class);

        // PR 4.1: application-facing @Primary — Lazy outermost, а не PwdRetry.
        assertThat(primary).isInstanceOf(LazyConnectionDataSourceProxy.class);

        executeSelect1(primary);

        execPsql("ALTER ROLE " + ROT_USER + " WITH PASSWORD '" + NEW_PWD + "';");

        // Симуляция ESO/Vault Agent: новый секрет материализован в конфигурацию Hikari.
        writePool.getHikariConfigMXBean().setPassword(NEW_PWD);
        readPool.getHikariConfigMXBean().setPassword(NEW_PWD);
        writePool.getHikariPoolMXBean().softEvictConnections();
        readPool.getHikariPoolMXBean().softEvictConnections();

        // Через full Spring chain (Lazy → Routing → PwdRetry → Hikari) соединение восстанавливается.
        executeSelect1(primary);
    }

    private void execPsql(String sql) throws Exception {
        cluster.execPsql(sql);
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    static class E7App {
    }
}
