package space.br1440.platform.healthchecks.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.common.HealthStatuses;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@DisplayName("PostgresHealthIndicator: unit")
class PostgresHealthIndicatorTest {

    @SuppressWarnings("SqlNoDataSourceInspection")
    private static final String VALIDATION_QUERY = "SELECT 1";

    private HikariDataSource hikariDataSource;
    private PostgresHealthProperties properties;
    private PoolHealthEvaluator poolHealthEvaluator;

    @BeforeEach
    void setUp() {
        hikariDataSource = createHikariDataSource();

        properties = new PostgresHealthProperties();
        properties.setProbes(Set.of(HealthProbe.READINESS));
        properties.setHealthCheckTimeout(Duration.ofSeconds(3));
        properties.getConnectionPool().setMonitoringEnabled(true);

        poolHealthEvaluator = new PoolHealthEvaluator(properties);
    }

    @AfterEach
    void tearDown() {
        if (hikariDataSource != null) {
            hikariDataSource.close();
        }
    }

    @Nested
    @DisplayName("checkConnectivity — SELECT 1")
    class ConnectivityCheck {

        @Test
        @DisplayName("Status=UP, database='PostgreSQL', validationQuery='SELECT 1' при успешном выполнении запроса")
        void returnsUpWhenConnectivityCheckPasses() {
            // Given
            PostgresHealthIndicator indicator = indicator(hikariDataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("database", "PostgreSQL")
                    .containsEntry("validationQuery", "SELECT 1")
                    .containsKey("queryTimeoutSeconds")
                    .containsKey("durationMs");
        }

        @Test
        @DisplayName("queryTimeoutSeconds в details совпадает со значением из properties")
        void shouldIncludeQueryTimeoutSecondsMatchingProperties() {
            // Given
            properties.setHealthCheckTimeout(Duration.ofSeconds(7));
            PostgresHealthIndicator indicator = indicator(hikariDataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getDetails().get("queryTimeoutSeconds")).isEqualTo(7);
        }

        @Test
        @DisplayName("ps.setQueryTimeout() вызывается с таймаутом из properties (в секундах)")
        void shouldSetQueryTimeoutOnPreparedStatement() throws SQLException {
            // Given
            properties.setHealthCheckTimeout(Duration.ofSeconds(5));
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);

            ResultSet rs = mock(ResultSet.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(VALIDATION_QUERY)).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true);

            ArgumentCaptor<Integer> timeoutCaptor = ArgumentCaptor.forClass(Integer.class);
            PostgresHealthIndicator indicator = indicator(dataSource);

            // When
            indicator.health();

            // Then
            verify(ps).setQueryTimeout(timeoutCaptor.capture());
            assertThat(timeoutCaptor.getValue()).isEqualTo(5);
        }

        @Test
        @DisplayName("Status=DOWN, error и errorType присутствуют когда DataSource бросает SQLException")
        void returnsDownWhenConnectionFails() throws SQLException {
            // Given
            DataSource brokenDataSource = mock(DataSource.class);
            when(brokenDataSource.getConnection())
                    .thenThrow(new SQLException("Connection refused"));
            PostgresHealthIndicator indicator = indicator(brokenDataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsKey("error")
                    .containsEntry("errorType", "SQLException")
                    .containsKey("durationMs");
        }

        @Test
        @DisplayName("Status=DOWN когда validation query не возвращает строк")
        void returnsDownWhenValidationQueryReturnsNoRows() throws SQLException {
            // Given
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(VALIDATION_QUERY)).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(false);
            PostgresHealthIndicator indicator = indicator(dataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("error");
        }

        @Test
        @DisplayName("Connection и PreparedStatement закрываются после успешного выполнения запроса")
        void shouldCloseResourcesAfterSuccessfulCheck() throws SQLException {
            // Given
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);

            ResultSet rs = mock(ResultSet.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(VALIDATION_QUERY)).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true);

            PostgresHealthIndicator indicator = indicator(dataSource);

            // When
            indicator.health();

            // Then
            verify(rs).close();
            verify(ps).close();
            verify(connection).close();
        }

        @Test
        @DisplayName("Connection закрывается даже если executeQuery бросает исключение")
        void shouldCloseConnectionAfterQueryFailure() throws SQLException {
            // Given
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(ps);
            when(ps.executeQuery()).thenThrow(new SQLException("Query timeout"));
            PostgresHealthIndicator indicator = indicator(dataSource);

            // When
            indicator.health();

            // Then
            verify(ps).close();
            verify(connection).close();
        }
    }

    @Nested
    @DisplayName("pool metrics — мониторинг HikariCP")
    class PoolMetrics {

        @Test
        @DisplayName("ключ 'pool' присутствует когда monitoring=enabled и DataSource=HikariCP")
        void includesPoolMetricsWhenMonitoringEnabled() {
            // Given
            PostgresHealthIndicator indicator = indicator(hikariDataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKey("pool");

            @SuppressWarnings("unchecked")
            Map<String, Object> pool = (Map<String, Object>) health.getDetails().get("pool");
            assertThat(pool).containsKeys(
                    "active", "idle", "total", "waiting",
                    "maxPoolSize", "minIdle", "availablePercent", "status");
        }

        @Test
        @DisplayName("ключ 'pool' отсутствует когда monitoring=disabled")
        void skipsPoolMetricsWhenMonitoringDisabled() {
            // Given
            properties.getConnectionPool().setMonitoringEnabled(false);
            PostgresHealthIndicator indicator = indicator(hikariDataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).doesNotContainKey("pool");
        }

        @Test
        @DisplayName("ключ 'pool' отсутствует когда DataSource не является HikariCP")
        void skipsPoolMetricsWhenDataSourceIsNotHikari() {
            // Given
            DriverManagerDataSource plain = new DriverManagerDataSource();
            plain.setDriverClassName("org.h2.Driver");
            plain.setUrl("jdbc:h2:mem:plain_ds;DB_CLOSE_DELAY=-1");
            plain.setUsername("sa");
            plain.setPassword("");
            PostgresHealthIndicator indicator = indicator(plain);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).doesNotContainKey("pool");
        }

        @Test
        @DisplayName("Status=UP, 'poolError' присутствует, 'pool' отсутствует когда PoolHealthEvaluator бросает исключение")
        void shouldAddPoolErrorWhenEvaluatorThrows() {
            // Given
            PoolHealthEvaluator failingEvaluator = mock(PoolHealthEvaluator.class);
            when(failingEvaluator.check(hikariDataSource))
                    .thenThrow(new RuntimeException("JMX not available"));
            PostgresHealthIndicator indicator = indicator(hikariDataSource, failingEvaluator);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsKey("poolError")
                    .doesNotContainKey("pool");
        }
    }

    @Nested
    @DisplayName("resolveHikariDataSource — разворачивание AOP proxy")
    class AopProxyUnwrapping {

        @Test
        @DisplayName("pool metrics присутствуют когда HikariDataSource обёрнут в Spring AOP proxy")
        void shouldUnwrapHikariFromAopProxy() {
            // Given
            ProxyFactory hikariProxyFactory = new ProxyFactory();
            hikariProxyFactory.setTarget(hikariDataSource);
            hikariProxyFactory.addInterface(DataSource.class);

            DataSource proxiedHikari = (DataSource) hikariProxyFactory.getProxy();
            PostgresHealthIndicator aopHikariIndicator = indicator(proxiedHikari);

            // When
            Health aopHikariHealth = aopHikariIndicator.health();

            // Then
            assertThat(aopHikariHealth.getStatus()).isEqualTo(Status.UP);
            assertThat(aopHikariHealth.getDetails()).containsKey("pool");
        }

        @Test
        @DisplayName("pool metrics отсутствуют когда под AOP proxy находится не HikariDataSource")
        void shouldReturnNullForNonHikariInsideAopProxy() {
            // Given
            DriverManagerDataSource plain = new DriverManagerDataSource();
            plain.setDriverClassName("org.h2.Driver");
            plain.setUrl("jdbc:h2:mem:aop_plain_ds;DB_CLOSE_DELAY=-1");
            plain.setUsername("sa");
            plain.setPassword("");
            ProxyFactory plainProxyFactory = new ProxyFactory();
            plainProxyFactory.setTarget(plain);
            plainProxyFactory.addInterface(DataSource.class);
            DataSource proxiedPlain = (DataSource) plainProxyFactory.getProxy();
            PostgresHealthIndicator aopPlainIndicator = indicator(proxiedPlain);

            // When
            Health aopPlainHealth = aopPlainIndicator.health();

            // Then
            assertThat(aopPlainHealth.getStatus()).isEqualTo(Status.UP);
            assertThat(aopPlainHealth.getDetails()).doesNotContainKey("pool");
        }

        @Test
        @DisplayName("исключение при getTargetSource().getTarget() игнорируется → pool metrics отсутствуют, статус UP")
        void shouldHandleExceptionFromAopProxyUnwrapping() throws Exception {
            // Given
            TargetSource brokenTargetSource = mock(TargetSource.class);
            when(brokenTargetSource.getTarget())
                    .thenThrow(new RuntimeException("TargetSource unavailable"));

            DataSource proxyLike = mock(DataSource.class,
                    withSettings().extraInterfaces(
                            SpringProxy.class,
                            Advised.class
                    ));
            when(((Advised) proxyLike).getTargetSource()).thenReturn(brokenTargetSource);
            when(proxyLike.getConnection()).thenAnswer(inv -> hikariDataSource.getConnection());

            PostgresHealthIndicator indicator = indicator(proxyLike);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .doesNotContainKey("pool")
                    .doesNotContainKey("poolError")
                    .containsKey("database")
                    .containsKey("durationMs");
        }
    }

    @Nested
    @DisplayName("buildHealth — маппинг PoolHealthResult → Status")
    class BuildHealthStatus {

        @Test
        @DisplayName("Status=DEGRADED когда PoolHealthResult.isDegraded()=true")
        void shouldReturnDegradedStatusWhenPoolIsDegraded() {
            // Given
            PoolHealthEvaluator degradedEvaluator = mock(PoolHealthEvaluator.class);
            PoolHealthEvaluator.PoolHealthResult degradedResult = new PoolHealthEvaluator.PoolHealthResult(
                    Map.of("status", "DEGRADED"),
                    PoolHealthEvaluator.PoolHealthResult.PoolStatus.DEGRADED
            );
            when(degradedEvaluator.check(hikariDataSource)).thenReturn(degradedResult);
            PostgresHealthIndicator degradedIndicator = indicator(hikariDataSource, degradedEvaluator);

            // When
            Health degradedHealth = degradedIndicator.health();

            // Then
            assertThat(degradedHealth.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        }

        @Test
        @DisplayName("Status=UP когда PoolHealthResult.isDegraded()=false")
        void shouldReturnUpWhenPoolResultIsHealthy() {
            // Given
            PostgresHealthIndicator indicator = indicator(hikariDataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("Status=UP когда monitoring=disabled (poolResult=null)")
        void shouldReturnUpWhenPoolResultIsNull() {
            // Given
            properties.getConnectionPool().setMonitoringEnabled(false);
            PostgresHealthIndicator indicator = indicator(hikariDataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }
    }

    @Nested
    @DisplayName("durationMs — измерение времени выполнения")
    class DurationMs {

        @Test
        @DisplayName("durationMs ≥ 0 на успешном пути (Status=UP)")
        void shouldIncludeDurationMsOnSuccessPath() {
            // Given
            PostgresHealthIndicator indicator = indicator(hikariDataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getDetails().get("durationMs"))
                    .isInstanceOf(Long.class);
            assertThat((Long) health.getDetails().get("durationMs"))
                    .isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("durationMs ≥ 0 на пути ошибки (Status=DOWN)")
        void shouldIncludeDurationMsOnErrorPath() throws SQLException {
            // Given
            DataSource brokenDataSource = mock(DataSource.class);
            when(brokenDataSource.getConnection())
                    .thenThrow(new SQLException("Connection refused"));
            PostgresHealthIndicator indicator = indicator(brokenDataSource);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getDetails().get("durationMs"))
                    .isInstanceOf(Long.class);
            assertThat((Long) health.getDetails().get("durationMs"))
                    .isGreaterThanOrEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("getSeverities() — делегирование в properties")
    class Severities {

        @Test
        @DisplayName("возвращает LIVENESS + READINESS когда оба настроены")
        void returnsSeveritiesFromProperties() {
            // Given
            properties.setProbes(EnumSet.of(HealthProbe.LIVENESS, HealthProbe.READINESS));
            PostgresHealthIndicator indicator = indicator(hikariDataSource);

            // When / Then
            assertThat(indicator.getProbes())
                    .containsExactlyInAnyOrder(HealthProbe.LIVENESS, HealthProbe.READINESS);
        }

        @Test
        @DisplayName("по умолчанию содержит только READINESS")
        void defaultsToReadinessSeverity() {
            // Given
            PostgresHealthIndicator indicator = indicator(hikariDataSource);

            // When / Then
            assertThat(indicator.getProbes())
                    .containsExactly(HealthProbe.READINESS);
        }
    }

    private PostgresHealthIndicator indicator(DataSource dataSource) {
        return new PostgresHealthIndicator(dataSource, properties, poolHealthEvaluator);
    }

    private PostgresHealthIndicator indicator(DataSource dataSource, PoolHealthEvaluator evaluator) {
        return new PostgresHealthIndicator(dataSource, properties, evaluator);
    }

    private HikariDataSource createHikariDataSource() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl("jdbc:h2:mem:health_test;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(3_000);
        config.setPoolName("PostgresHealthTest");
        return new HikariDataSource(config);
    }
}
