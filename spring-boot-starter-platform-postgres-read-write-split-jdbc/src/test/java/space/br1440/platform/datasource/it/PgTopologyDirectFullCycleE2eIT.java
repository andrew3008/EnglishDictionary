package space.br1440.platform.datasource.it;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.datasource.postgres.PgClusterEnvironmentPostProcessor;
import space.br1440.platform.datasource.postgres.internal.bootstrap.PgPropertySourceRegistrar;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.support.junit.ClusterRoutingItProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-тест полного жизненного цикла стартера.
 *
 * <p>Звено 1 (EPP): прямой вызов {@link PgClusterEnvironmentPostProcessor} на
 * {@link ConfigurableEnvironment} с raw {@code PG_*} — тот же контракт, что в production,
 * без зависимости от порядка {@code SpringApplication} listeners.
 *
 * <p>Звенья 2–3 (binding + бины): {@code SpringApplication.run()} с canonical keys в
 * {@code defaultProperties} (как остальные cluster IT).
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PG_TOPOLOGY=DIRECT — полный жизненный цикл (EPP → @ConfigurationProperties → бины)")
class PgTopologyDirectFullCycleE2eIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("e2e_db")
            .withUsername("e2e_user")
            .withPassword("e2e_pass");

    private ConfigurableEnvironment eppEnvironment;
    private ConfigurableApplicationContext context;

    @BeforeAll
    void startContext() {
        PG.start();

        String pgNodes = PG.getHost() + ":" + PG.getFirstMappedPort();
        SpringApplication app = new SpringApplication(E2eApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        eppEnvironment = ClusterRoutingItProperties.environmentWithRawPgEnv(
                pgNodes,
                PG.getDatabaseName(),
                PG.getUsername(),
                PG.getPassword(),
                "ApplicationName=e2e-cycle-test;tcpKeepAlive=true");
        new PgClusterEnvironmentPostProcessor(new NoOpDeferredLogFactory())
                .postProcessEnvironment(eppEnvironment, app);

        Map<String, Object> defaultProperties = new HashMap<>();
        defaultProperties.put("spring.main.web-application-type", "none");
        defaultProperties.put("spring.main.banner-mode", "off");
        defaultProperties.put("logging.level.space.br1440.platform.datasource", "DEBUG");
        ClusterRoutingItProperties.applyClientActivation(defaultProperties);
        ClusterRoutingItProperties.applyCanonicalCluster(
                defaultProperties, pgNodes, PG.getDatabaseName(), PG.getUsername(), PG.getPassword());
        defaultProperties.put(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size", "2");
        defaultProperties.put(PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size", "2");
        defaultProperties.put(PgClusterProperties.PREFIX + ".pool.write.connection-test-query", "SELECT 1");
        defaultProperties.put(PgClusterProperties.PREFIX + ".pool.read.connection-test-query", "SELECT 1");

        app.setDefaultProperties(defaultProperties);
        this.context = app.run();
    }

    @AfterAll
    void stopContext() {
        if (context != null && context.isActive()) {
            context.close();
        }
        PG.stop();
    }

    @Nested
    @DisplayName("Звено 1: EnvironmentPostProcessor")
    class EppLink {

        @Test
        @DisplayName("EPP зарегистрировал свой MapPropertySource в Environment")
        void eppRegisteredItsPropertySource() {
            PropertySource<?> source = eppEnvironment.getPropertySources()
                    .get(PgPropertySourceRegistrar.PROPERTY_SOURCE_NAME);

            assertThat(source)
                    .as("EPP должен зарегистрировать MapPropertySource с именем '%s'",
                            PgPropertySourceRegistrar.PROPERTY_SOURCE_NAME)
                    .isNotNull();
        }

        @Test
        @DisplayName("EPP инжектировал канонические ключи в Environment")
        void eppInjectedCanonicalProperties() {
            assertThat(eppEnvironment.getProperty(PgClusterProperties.PREFIX + ".nodes[0].host"))
                    .as("PG_CLUSTER_NODES должен попасть в nodes[0].host")
                    .isEqualTo(PG.getHost());
            assertThat(eppEnvironment.getProperty(PgClusterProperties.PREFIX + ".nodes[0].port", Integer.class))
                    .as("PG_CLUSTER_NODES должен попасть в nodes[0].port")
                    .isEqualTo(PG.getFirstMappedPort());

            assertThat(eppEnvironment.getProperty(PgClusterProperties.PREFIX + ".database"))
                    .isEqualTo(PG.getDatabaseName());
            assertThat(eppEnvironment.getProperty(PgClusterProperties.PREFIX + ".credentials.username"))
                    .isEqualTo(PG.getUsername());
            assertThat(eppEnvironment.getProperty(PgClusterProperties.PREFIX + ".credentials.password"))
                    .isEqualTo(PG.getPassword());
        }

        @Test
        @DisplayName("EPP распарсил PG_CONNECTION_PROPERTIES в jdbc.properties.* и сохранил регистр ключей")
        void eppParsedConnectionProperties() {
            assertThat(eppEnvironment.getProperty(PgClusterProperties.PREFIX + ".jdbc.properties.ApplicationName"))
                    .isEqualTo("e2e-cycle-test");
            assertThat(eppEnvironment.getProperty(PgClusterProperties.PREFIX + ".jdbc.properties.tcpKeepAlive"))
                    .isEqualTo("true");
        }
    }

    @Nested
    @DisplayName("Звено 2: @ConfigurationProperties biding")
    class ConfigurationPropertiesLink {

        @Test
        @DisplayName("PgClusterProperties забиндился из канонических ключей")
        void propertiesBoundFromCanonicalKeys() {
            PgClusterProperties props = context.getBean(PgClusterProperties.class);

            assertThat(props.nodes().stream().map(PgNode::toHostPort))
                    .as("nodes должен прийти из canonical keys")
                    .containsExactly(PG.getHost() + ":" + PG.getFirstMappedPort());
            assertThat(props.database()).isEqualTo(PG.getDatabaseName());
            assertThat(props.credentials().username()).isEqualTo(PG.getUsername());
            assertThat(props.credentials().password()).isEqualTo(PG.getPassword());
        }
    }

    @Nested
    @DisplayName("Звено 3: HikariCP бины и реальное соединение")
    class BeanWiringLink {

        @Test
        @DisplayName("Контекст содержит write- и read-пулы HikariCP")
        void hikariPoolsArePresent() {
            HikariDataSource write = context.getBean("writeDataSource", HikariDataSource.class);
            HikariDataSource read = context.getBean("readDataSource", HikariDataSource.class);

            assertThat(write).isNotNull();
            assertThat(read).isNotNull();
            assertThat(read.isReadOnly()).isTrue();
        }

        @Test
        @DisplayName("Реальный SELECT 1 через RoutingDataSource возвращает 1")
        void routingDataSourceCanQuery() throws Exception {
            DataSource dataSource = context.getBean("dataSource", DataSource.class);
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }
        }
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class E2eApp {
    }

    private static final class NoOpDeferredLogFactory implements DeferredLogFactory {
        @Override
        public org.apache.commons.logging.Log getLog(java.util.function.Supplier<org.apache.commons.logging.Log> destination) {
            return destination.get();
        }
    }
}
