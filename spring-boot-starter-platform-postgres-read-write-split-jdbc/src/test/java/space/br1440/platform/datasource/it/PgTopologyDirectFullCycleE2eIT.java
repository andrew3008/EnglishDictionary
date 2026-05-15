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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.environment.PostgresClusterEnvironmentPostProcessor;
import space.br1440.platform.datasource.configuration.condition.OnPgTopologyDirectCondition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-тест полного жизненного цикла стартера, демонстрирующий, что цепочка
 * <i>{@code PG_*} «environment variables» → {@link PostgresClusterEnvironmentPostProcessor} →
 * канонические {@code platform.datasource.postgres-cluster.*} → {@link PostgresClusterDataSourceProperties}
 * → бины автоконфигурации</i> работает на полноценном {@link SpringApplication} с
 * реальным PostgreSQL (Testcontainers).
 *
 * <h3>Чем этот IT отличается от {@code DataSourceRoutingIntegrationTest}</h3>
 * <p>{@code DataSourceRoutingIntegrationTest} использует {@code @SpringBootTest} +
 * {@code @DynamicPropertySource}, который инжектируется <i>после</i> EPP.
 * Поэтому там {@code PG_*} ENV-переменные нельзя использовать напрямую — задаются
 * сразу канонические {@code platform.datasource.postgres-cluster.*} ключи.
 *
 * <p>Здесь, наоборот, мы <b>обязаны</b> прокачать всю цепочку: имитируем Kubernetes-окружение,
 * подавая {@code PG_*} как «default properties» в {@link SpringApplication#setDefaultProperties(Map)}.
 * При прямом вызове {@code SpringApplication.run()} EPP запускается штатно, и тест проверяет:
 * <ol>
 *   <li>EPP действительно зарегистрировал {@link PostgresClusterEnvironmentPostProcessor#PROPERTY_SOURCE_NAME}
 *       в {@code Environment};</li>
 *   <li>канонические ключи действительно появились в {@code Environment} с правильными значениями;</li>
 *   <li>{@link PostgresClusterDataSourceProperties} забиндился из этих канонических ключей;</li>
 *   <li>HikariCP-пулы действительно открылись против реальной БД и могут выполнить запрос.</li>
 * </ol>
 *
 * <h3>Почему не {@code @SpringBootTest} + {@code ApplicationContextInitializer}</h3>
 * <p>Spring Test Framework инжектирует {@code ApplicationContextInitializer} <i>после</i>
 * фазы {@code prepareEnvironment} — то есть после запуска EPP. {@code @DynamicPropertySource}
 * — ещё позднее. Поэтому единственный надёжный способ подать «настоящие» ENV-переменные до EPP
 * без побочных эффектов уровня JVM (например, {@code System.setProperty}, портящих другие тесты)
 * — это прямой {@code SpringApplication.run()} с {@code setDefaultProperties}.
 * Именно его и использует production-симуляция в этом IT.
 *
 * <h3>Контейнер</h3>
 * <p>Запускается один PostgreSQL-инстанс. Оба пула стартера (write и read) подключаются
 * к нему — это валидно для проверки end-to-end-цикла активации; кластерные сценарии
 * (primary+replica, switchover) проверяются в отдельных {@code *ClusterIT}.
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

    private ConfigurableApplicationContext context;

    @BeforeAll
    void startContext() {
        PG.start();

        Map<String, Object> defaultProperties = new HashMap<>();
        defaultProperties.put("spring.main.web-application-type", "none");
        defaultProperties.put("spring.main.banner-mode", "off");
        defaultProperties.put("logging.level.space.br1440.platform.datasource", "DEBUG");
        defaultProperties.put(OnPgTopologyDirectCondition.ENV_VAR, OnPgTopologyDirectCondition.DIRECT);
        defaultProperties.put("PG_CLUSTER_NODES", PG.getHost() + ":" + PG.getFirstMappedPort());
        defaultProperties.put("PG_DATABASE", PG.getDatabaseName());
        defaultProperties.put("PG_USERNAME", PG.getUsername());
        defaultProperties.put("PG_PASSWORD", PG.getPassword());
        defaultProperties.put("PG_CONNECTION_PROPERTIES", "ApplicationName=e2e-cycle-test;tcpKeepAlive=true");
        defaultProperties.put("platform.datasource.postgres-cluster.hikari.write.maximum-pool-size", "2");
        defaultProperties.put("platform.datasource.postgres-cluster.hikari.read.maximum-pool-size", "2");
        defaultProperties.put("platform.datasource.postgres-cluster.hikari.write.connection-test-query", "SELECT 1");
        defaultProperties.put("platform.datasource.postgres-cluster.hikari.read.connection-test-query", "SELECT 1");

        SpringApplication app = new SpringApplication(E2eApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
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
            ConfigurableEnvironment env = (ConfigurableEnvironment) context.getEnvironment();
            PropertySource<?> source = env.getPropertySources()
                    .get(PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);

            assertThat(source)
                    .as("EPP должен зарегистрировать MapPropertySource с именем '%s'",
                            PostgresClusterEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)
                    .isNotNull();
        }

        @Test
        @DisplayName("EPP инжектировал канонические ключи в Environment")
        void eppInjectedCanonicalProperties() {
            ConfigurableEnvironment env = (ConfigurableEnvironment) context.getEnvironment();

            assertThat(env.getProperty("platform.datasource.postgres-cluster.nodes"))
                    .as("PG_CLUSTER_NODES должен попасть в platform.datasource.postgres-cluster.nodes")
                    .isEqualTo(PG.getHost() + ":" + PG.getFirstMappedPort());

            assertThat(env.getProperty("platform.datasource.postgres-cluster.database"))
                    .isEqualTo(PG.getDatabaseName());
            assertThat(env.getProperty("platform.datasource.postgres-cluster.credentials.username"))
                    .isEqualTo(PG.getUsername());
            assertThat(env.getProperty("platform.datasource.postgres-cluster.credentials.password"))
                    .isEqualTo(PG.getPassword());
        }

        @Test
        @DisplayName("EPP распарсил PG_CONNECTION_PROPERTIES в connection-properties.* и сохранил регистр ключей")
        void eppParsedConnectionProperties() {
            ConfigurableEnvironment env = (ConfigurableEnvironment) context.getEnvironment();

            assertThat(env.getProperty("platform.datasource.postgres-cluster.connection-properties.ApplicationName"))
                    .isEqualTo("e2e-cycle-test");
            assertThat(env.getProperty("platform.datasource.postgres-cluster.connection-properties.tcpKeepAlive"))
                    .isEqualTo("true");
        }
    }

    @Nested
    @DisplayName("Звено 2: @ConfigurationProperties biding")
    class ConfigurationPropertiesLink {

        @Test
        @DisplayName("PostgresClusterDataSourceProperties забиндился из канонических ключей")
        void propertiesBoundFromCanonicalKeys() {
            PostgresClusterDataSourceProperties props =
                    context.getBean(PostgresClusterDataSourceProperties.class);

            assertThat(props.getNodes())
                    .as("nodes должен прийти из PG_CLUSTER_NODES через EPP")
                    .containsExactly(PG.getHost() + ":" + PG.getFirstMappedPort());
            assertThat(props.getDatabase()).isEqualTo(PG.getDatabaseName());
            assertThat(props.getCredentials().getUsername()).isEqualTo(PG.getUsername());
            assertThat(props.getCredentials().getPassword()).isEqualTo(PG.getPassword());

            assertThat(props.getConnectionProperties())
                    .as("connection-properties должны быть распарсены из PG_CONNECTION_PROPERTIES, "
                            + "user/password ключи отфильтрованы")
                    .containsEntry("ApplicationName", "e2e-cycle-test")
                    .containsEntry("tcpKeepAlive", "true")
                    .doesNotContainKey("user")
                    .doesNotContainKey("password");
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
        @SuppressWarnings("SqlNoDataSourceInspection")
        void routingDataSourceCanQuery() throws Exception {
            DataSource ds = context.getBean("dataSource", DataSource.class);
            // noinspection SqlNoDataSourceInspection -- E2E (Testcontainers); глобальный data source IDE не нужен.
            final String pingSql = "SELECT 1";
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(pingSql)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class E2eApp {
    }
}
