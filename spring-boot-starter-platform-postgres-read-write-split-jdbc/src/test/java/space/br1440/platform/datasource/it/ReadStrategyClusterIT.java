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
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;
import space.br1440.platform.datasource.support.junit.ClusterRoutingItProperties;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозная проверка двух {@link ReadMode} на реальном primary+replica кластере.
 * <p>
 * В новом контракте режим чтения задаётся через канонический ключ
 * {@value PgClusterProperties#PREFIX}.read.mode} и биндится в
 * {@link PgClusterProperties} штатным механизмом
 * {@code @ConfigurationProperties}. Этот IT поднимает два независимых Spring-контекста
 * на одном и том же primary+replica кластере: один с {@link ReadMode#ANY_NODE_LOAD_BALANCED}
 * (programmatic default), другой с {@link ReadMode#SECONDARY_PREFERRED} (override через
 * default-properties в {@link SpringApplication#setDefaultProperties(Map)}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
@DisplayName("ReadStrategy × живой PostgreSQL кластер")
class ReadStrategyClusterIT {

    private ConfigurableApplicationContext secondaryOnlyContext;
    private ConfigurableApplicationContext loadBalancedContext;

    @BeforeAll
    void startContexts(PgCluster cluster) {
        Map<String, Object> secondaryOnlyOverride = Map.of(
                PgClusterProperties.PREFIX + ".read.mode", "SECONDARY_PREFERRED"
        );
        this.secondaryOnlyContext = startContext(cluster, ClusterApp.class, secondaryOnlyOverride);
        Map<String, Object> loadBalancedOverride = Map.of(
                PgClusterProperties.PREFIX + ".read.mode", "ANY_NODE_LOAD_BALANCED"
        );
        this.loadBalancedContext = startContext(cluster, ClusterApp.class, loadBalancedOverride);
    }

    @AfterAll
    void stopContexts() {
        if (secondaryOnlyContext != null && secondaryOnlyContext.isActive()) secondaryOnlyContext.close();
        if (loadBalancedContext != null && loadBalancedContext.isActive()) loadBalancedContext.close();
    }

    @Test
    @DisplayName("SECONDARY_ONLY: read-пул URL содержит targetServerType=preferSecondary, без loadBalanceHosts")
    void secondaryOnly_urlConfiguredCorrectly() {
        HikariDataSource read = (HikariDataSource) secondaryOnlyContext.getBean("readDataSource");

        RoutingAssertions.assertThat(read)
                .isReadOnly()
                .jdbcUrl()
                .hasTargetServerType("preferSecondary")
                .hasNoParam("loadBalanceHosts");
    }

    @Test
    @DisplayName("LOAD_BALANCED: read-пул URL содержит targetServerType=any и loadBalanceHosts=true")
    void loadBalanced_urlConfiguredCorrectly() {
        HikariDataSource read = (HikariDataSource) loadBalancedContext.getBean("readDataSource");

        RoutingAssertions.assertThat(read)
                .isReadOnly()
                .jdbcUrl()
                .hasTargetServerType("any")
                .hasParam("loadBalanceHosts", "true")
                .hasHostCount(2);
    }

    @Test
    @DisplayName("SECONDARY_ONLY: read-соединение приходит на ноду в hot-standby режиме (pg_is_in_recovery=true)")
    void secondaryOnly_connectsToSecondary() throws Exception {
        HikariDataSource read = (HikariDataSource) secondaryOnlyContext.getBean("readDataSource");
        try (Connection conn = read.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT pg_is_in_recovery()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1))
                    .as("pg_is_in_recovery() must be TRUE for secondary connections")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("LOAD_BALANCED: read-пул может подключиться, поскольку targetServerType=any")
    void loadBalanced_canConnect() throws Exception {
        HikariDataSource read = (HikariDataSource) loadBalancedContext.getBean("readDataSource");
        try (Connection conn = read.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Write-пул не зависит от ReadStrategy: всегда targetServerType=primary")
    void writePool_unaffectedByReadStrategy() {
        HikariDataSource writeSecondary = (HikariDataSource) secondaryOnlyContext.getBean("writeDataSource");
        HikariDataSource writeBalanced = (HikariDataSource) loadBalancedContext.getBean("writeDataSource");

        RoutingAssertions.assertThat(writeSecondary).isNotReadOnly()
                .jdbcUrl().hasTargetServerType("primary");
        RoutingAssertions.assertThat(writeBalanced).isNotReadOnly()
                .jdbcUrl().hasTargetServerType("primary");
    }

    private ConfigurableApplicationContext startContext(PgCluster cluster,
                                                        Class<?> applicationClass,
                                                        Map<String, Object> overrides) {
        Map<String, Object> properties = new HashMap<>(ClusterRoutingItProperties.baseClusterContextProperties(cluster));
        properties.put(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size", "3");
        properties.put(PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size", "3");
        properties.putAll(overrides);

        SpringApplication app = new SpringApplication(applicationClass);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(properties);
        return app.run();
    }

    /**
     * Минимальное Spring Boot приложение для IT: без UI, без авто-датасорса от Spring
     * (только наш стартер). Активируется через {@code PG_TOPOLOGY=DIRECT} в default-properties.
     */
    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class ClusterApp {
    }
}
