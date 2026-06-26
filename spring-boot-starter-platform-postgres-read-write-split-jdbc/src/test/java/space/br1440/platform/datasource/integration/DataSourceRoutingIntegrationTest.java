package space.br1440.platform.datasource.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.routing.UseReadReplica;
import space.br1440.platform.datasource.postgres.PostgresDataSourceRoutingAutoConfiguration;
import space.br1440.platform.datasource.postgres.internal.bootstrap.OnClientDataSourceRoutingCondition;
import space.br1440.platform.datasource.postgres.internal.integration.health.DataSourceRoutingHealthIndicator;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.internal.proxy.lifecycle.DataSourceRoutingLifecycle;
import space.br1440.platform.datasource.postgres.internal.proxy.aop.NonTransactionalRoutingAspect;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты с реальным PostgreSQL (Testcontainers).
 * <p>
 * Testcontainers запускает один PostgreSQL-инстанс. Оба пула (write и read)
 * подключаются к нему — это позволяет проверить полный lifecycle автоконфигурации,
 * маршрутизацию и работу health-check без кластерной топологии.
 *
 * <h3>Контракт активации</h3>
 * <p>В тестовый {@code Environment} через {@link DynamicPropertyRegistry} подаются:
 * <ul>
 *   <li>{@code datasource-routing-mode=CLIENT} — активирует {@link OnClientDataSourceRoutingCondition};</li>
 *   <li>{@value PgClusterProperties#PREFIX}.* — канонические Spring-свойства, биндятся в {@link PgClusterProperties}.</li>
 * </ul>
 *
 * <p><b>Почему не {@code PG_*} env-переменные.</b> {@code @DynamicPropertySource}
 * добавляется в {@code Environment} <i>после</i> запуска {@code EnvironmentPostProcessor}-ов
 * (это документированное ограничение Spring TestContext, см. SPR-26148).
 * Поэтому в этом IT-сценарии задаём канонические Spring-свойства напрямую.
 * Сквозная проверка цикла «{@code PG_*} ENV → EPP → canonical properties → биндинг»
 * выполняется в {@link PgClusterEnvironmentPostProcessorBootstrapTest} (bootstrap, без Docker)
 * и в {@code PgTopologyDirectFullCycleE2eIT} (E2E с Testcontainers).
 *
 * <p>Кластерные сценарии (switchover, stale connection eviction) требуют multi-node setup
 * и тестируются отдельно в E2E-стенде.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {
        PostgresDataSourceRoutingAutoConfiguration.class,
        DataSourceRoutingIntegrationTest.TestService.class
})
@EnableAutoConfiguration
@DisplayName("DataSourceRouting — интеграция с PostgreSQL")
class DataSourceRoutingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("routing_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add(OnClientDataSourceRoutingCondition.PROPERTY, () -> "CLIENT");
        // nodes биндится как indexed list PgNode (host/port), как это делает EPP в проде.
        registry.add(PgClusterProperties.PREFIX + ".nodes[0].host", PG::getHost);
        registry.add(PgClusterProperties.PREFIX + ".nodes[0].port", PG::getFirstMappedPort);
        registry.add(PgClusterProperties.PREFIX + ".database", PG::getDatabaseName);
        registry.add(PgClusterProperties.PREFIX + ".credentials.username", PG::getUsername);
        registry.add(PgClusterProperties.PREFIX + ".credentials.password", PG::getPassword);
        registry.add(PgClusterProperties.PREFIX + ".pool.write.pool-name", () -> "it-write-pool");
        registry.add(PgClusterProperties.PREFIX + ".pool.read.pool-name", () -> "it-read-pool");
        registry.add(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size", () -> "2");
        registry.add(PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size", () -> "2");
        registry.add(PgClusterProperties.PREFIX + ".pool.write.connection-test-query", () -> "SELECT 1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private TestService testService;

    @Nested
    @DisplayName("Автоконфигурация — бины")
    class AutoConfigurationBeans {

        @Test
        void allCoreBeans_arePresent() {
            assertThat(ctx.getBean(RoutingDataSource.class)).isNotNull();
            assertThat(ctx.getBean(NonTransactionalRoutingAspect.class)).isNotNull();
            assertThat(ctx.getBean(DataSourceRoutingLifecycle.class)).isNotNull();
        }

        @Test
        void primaryDataSource_isLazyConnectionProxy() {
            DataSource primary = ctx.getBean("dataSource", DataSource.class);
            assertThat(primary).isInstanceOf(org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy.class);
        }

        @Test
        void writeAndReadPools_areHikariDataSource() {
            HikariDataSource write = ctx.getBean("writeDataSource", HikariDataSource.class);
            HikariDataSource read = ctx.getBean("readDataSource", HikariDataSource.class);

            assertThat(write.getPoolName()).isEqualTo("it-write-pool");
            assertThat(read.getPoolName()).isEqualTo("it-read-pool");
            assertThat(read.isReadOnly()).isTrue();
        }
    }

    @Nested
    @DisplayName("Маршрутизация")
    class Routing {

        @Test
        void defaultRouting_goesToWritePool() throws SQLException {
            DataSource ds = ctx.getBean("dataSource", DataSource.class);
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        @Test
        void explicitReadContext_goesToReadPool() throws SQLException {
            DataSource ds = ctx.getBean("dataSource", DataSource.class);
            DataSourceContextHolder.set(DataSourceType.READ);
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                assertThat(rs.next()).isTrue();
            } finally {
                DataSourceContextHolder.clear();
            }
        }

        @Test
        void transactionalReadOnly_executesSuccessfully() {
            Integer result = testService.readOnlyQuery();
            assertThat(result).isEqualTo(1);
        }

        @Test
        void transactionalWrite_executesSuccessfully() {
            Integer result = testService.writeQuery();
            assertThat(result).isEqualTo(1);
        }

        @Test
        void routeForRead_executesSuccessfully() {
            Integer result = testService.readPoolQuery();
            assertThat(result).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Health indicator")
    class HealthCheck {

        @Test
        @SuppressWarnings("unchecked")
        void healthIndicator_reportsUp_withIntentDetailsAndPgRole() {
            DataSourceRoutingHealthIndicator indicator = ctx.getBean(DataSourceRoutingHealthIndicator.class);
            var health = indicator.health();

            assertThat(health.getStatus().getCode()).isEqualTo("UP");
            assertThat(health.getDetails()).containsKey("readMode");

            Map<String, Object> write = (Map<String, Object>) health.getDetails().get("write");
            Map<String, Object> read = (Map<String, Object>) health.getDetails().get("read");

            assertThat(write).isNotNull();
            assertThat(write).containsEntry("status", "UP");
            assertThat(write).containsKey("pgRole");

            assertThat(read).isNotNull();
            assertThat(read).containsEntry("status", "UP");
            assertThat(read).containsKey("pgRole");
        }

        @Test
        void healthIndicator_isRegisteredUnderRoutingBeanName() {
            assertThat(ctx.containsBean("routingHealthIndicator")).isTrue();
        }
    }

    @Service
    static class TestService {

        @Autowired
        private DataSource dataSource;

        @Transactional(readOnly = true)
        public Integer readOnlyQuery() {
            return executeSelect1();
        }

        @Transactional
        public Integer writeQuery() {
            return executeSelect1();
        }

        @UseReadReplica
        public Integer readPoolQuery() {
            return executeSelect1();
        }

        private Integer executeSelect1() {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                return rs.next() ? rs.getInt(1) : null;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
