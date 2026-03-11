package space.br1440.platform.healthchecks.postgres;

import com.github.dockerjava.api.command.PauseContainerCmd;
import com.github.dockerjava.api.command.UnpauseContainerCmd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.common.HealthStatuses;
import space.br1440.platform.healthchecks.configuration.PostgresHealthAutoConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@EnableConfigurationProperties(PostgresHealthProperties.class)
@DisplayName("PostgresHealthIndicator — интеграция")
class PostgresHealthIndicatorIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl() + "&socketTimeout=3&connectTimeout=3");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        registry.add("platform.health.postgres.enabled", () -> "true");
        registry.add("platform.health.postgres.severity", () -> "READINESS");
        registry.add("platform.health.postgres.health-check-timeout", () -> "5s");
        registry.add("platform.health.postgres.connection-pool.monitoring-enabled", () -> "true");
    }

    @SpringBootApplication
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, PostgresHealthAutoConfiguration.class})
    static class TestConfig {

        @Bean
        public PoolHealthEvaluator poolHealthEvaluator(PostgresHealthProperties properties) {
            return new PoolHealthEvaluator(properties);
        }

        @Bean
        public PostgresHealthIndicator postgresHealthIndicator(DataSource dataSource,
                                                               PostgresHealthProperties properties,
                                                               PoolHealthEvaluator poolHealthEvaluator) {
            return new PostgresHealthIndicator(dataSource, properties, poolHealthEvaluator);
        }
    }

    @Autowired
    private PostgresHealthIndicator postgresHealthIndicator;

    @Test
    @DisplayName("возвращает UP при подключении к реальному PostgreSQL")
    void returnsUpWhenConnectedToPostgres() {
        // When
        Health health = postgresHealthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("database", "PostgreSQL")
                .containsEntry("validationQuery", "SELECT 1")
                .containsKey("queryTimeoutSeconds")
                .containsKey("durationMs");
    }

    @Test
    @DisplayName("durationMs неотрицательный и в пределах query timeout")
    void durationMsIsWithinQueryTimeout() {
        // When
        Health health = postgresHealthIndicator.health();

        // Then
        long durationMs = (long) health.getDetails().get("durationMs");
        assertThat(durationMs)
                .isNotNegative()
                .isLessThan(Duration.ofSeconds(5).toMillis());
    }

    @Test
    @DisplayName("включает метрики пула HikariCP при включённом мониторинге")
    void includesHikariPoolMetrics() {
        // When
        Health health = postgresHealthIndicator.health();

        // Then
        assertThat(health.getDetails()).containsKey("pool");

        @SuppressWarnings("unchecked")
        Map<String, Object> pool = (Map<String, Object>) health.getDetails().get("pool");
        assertThat(pool).containsKeys(
                "active", "idle", "total", "waiting",
                "maxPoolSize", "minIdle", "availablePercent", "status"
        );

        assertThat((Integer) pool.get("total")).isGreaterThanOrEqualTo(0);
        assertThat((Integer) pool.get("availablePercent")).isBetween(0, 100);
        assertThat(pool.get("status")).isIn("HEALTHY", "DEGRADED");
    }

    @Test
    @DisplayName("статус пула HEALTHY при нормальной нагрузке")
    void poolStatusIsHealthyUnderNormalLoad() {
        // When
        Health health = postgresHealthIndicator.health();

        // Then
        @SuppressWarnings("unchecked")
        Map<String, Object> pool = (Map<String, Object>) health.getDetails().get("pool");

        assertThat(pool.get("status")).isEqualTo("HEALTHY");
        assertThat(pool).doesNotContainKey("warnings");
    }

    @Test
    @DisplayName("severities совпадают с настроенными значениями")
    void severitiesMatchConfiguration() {
        assertThat(postgresHealthIndicator.getProbes())
                .containsExactlyInAnyOrder(HealthProbe.READINESS);
    }

    @Test
    @DisplayName("возвращает DOWN при временной недоступности postgres (pause/unpause)")
    void returnsDownWhenPostgresUnavailable() {
        String containerId = postgres.getContainerId();
        try (PauseContainerCmd pause = postgres.getDockerClient().pauseContainerCmd(containerId)) {
            pause.exec();
        }

        try {
            Health health = postgresHealthIndicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsKey("error")
                    .containsKey("errorType")
                    .containsKey("durationMs");
        } finally {
            try (UnpauseContainerCmd unpause = postgres.getDockerClient().unpauseContainerCmd(containerId)) {
                unpause.exec();
            }
        }
    }

    @Test
    @DisplayName("queryTimeoutSeconds в details равен настроенному query-timeout (5s)")
    void shouldIncludeQueryTimeoutSecondsInDetails() {
        Health health = postgresHealthIndicator.health();

        assertThat(health.getDetails())
                .containsEntry("queryTimeoutSeconds", 5);
    }

    @Test
    @DisplayName("возвращает UP после переподключения (pause → DOWN, unpause → UP, без DirtiesContext)")
    void shouldReturnUpAfterReconnecting() {
        String containerId = postgres.getContainerId();

        // Pause: имитируем сетевой сбой без изменения порта контейнера
        try (PauseContainerCmd pause = postgres.getDockerClient().pauseContainerCmd(containerId)) {
            pause.exec();
        }

        try {
            Health downHealth = postgresHealthIndicator.health();
            assertThat(downHealth.getStatus()).isEqualTo(Status.DOWN);
        } finally {
            // Unpause: восстанавливаем доступность — гарантировано даже при AssertionError
            try (UnpauseContainerCmd unpause = postgres.getDockerClient().unpauseContainerCmd(containerId)) {
                unpause.exec();
            }
        }

        // HikariCP должен переподключиться к тому же порту — ждём готовности
        awaitPostgresReady();

        Health upHealth = postgresHealthIndicator.health();
        assertThat(upHealth.getStatus()).isEqualTo(Status.UP);
        assertThat(upHealth.getDetails()).containsKey("database");
    }

    @Test
    @DisplayName("обрабатывает параллельные health checks — 5 потоков, без исключений, статус не UNKNOWN/ошибка")
    void shouldHandleConcurrentHealthChecks() throws InterruptedException {
        int threads = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Health> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        boolean started = startLatch.await(30, TimeUnit.SECONDS);
                        if (!started) {
                            throw new AssertionError("Start latch timed out — test setup failed");
                        }
                        results.add(postgresHealthIndicator.health());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errors.add(e);
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(30, TimeUnit.SECONDS))
                    .as("All health checks completed within timeout")
                    .isTrue();
        }

        // Все потоки завершились без необработанных исключений
        assertThat(errors)
                .as("No unexpected exceptions in concurrent health checks")
                .isEmpty();

        // Все потоки получили валидный Health-ответ — UP или DEGRADED (допустимо при 100% занятости пула)
        // Тест проверяет thread-safety, а не конкретный статус при пиковой нагрузке
        assertThat(results)
                .hasSize(threads)
                .allMatch(h -> Status.UP.equals(h.getStatus()) || HealthStatuses.DEGRADED.equals(h.getStatus()));
    }

    private void awaitPostgresReady() {
        // Даём HikariCP время провалидировать пул после восстановления соединений
        try { Thread.sleep(500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int maxAttempts = 30;
        for (int i = 0; i < maxAttempts; i++) {
            Health h = postgresHealthIndicator.health();
            if (Status.UP.equals(h.getStatus())) {
                return;
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Postgres", e);
            }
        }
        throw new AssertionError("Postgres did not return UP within " + maxAttempts + " seconds");
    }

    @Nested
    @DisplayName("при minAvailablePercent=100 (пороги пула превышены)")
    @SpringBootTest(
            classes = TestConfig.class,
            properties = "platform.health.postgres.connection-pool.min-available-percent=100"
    )
    class DegradedWhenPoolThresholdsBreached {

        @DynamicPropertySource
        static void configureNested(DynamicPropertyRegistry registry) {
            PostgresHealthIndicatorIntegrationTest.configureProperties(registry);
            registry.add("platform.health.postgres.connection-pool.min-available-percent", () -> "100");
        }

        @Autowired
        private PostgresHealthIndicator postgresHealthIndicator;

        @Autowired
        private DataSource dataSource;

        @Test
        @DisplayName("возвращает DEGRADED и pool содержит ключ warnings")
        void shouldReturnDegradedWhenPoolThresholdsBreached() throws Exception {
            try (@SuppressWarnings("unused") Connection ignored = dataSource.getConnection()) {
                Health health = postgresHealthIndicator.health();

                assertThat(health.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
                assertThat(health.getDetails()).containsKey("pool");

                @SuppressWarnings("unchecked")
                Map<String, Object> pool = (Map<String, Object>) health.getDetails().get("pool");
                assertThat(pool).containsKey("warnings");
                assertThat((List<?>) pool.get("warnings")).isNotEmpty();
                assertThat(pool.get("status")).isEqualTo("DEGRADED");
            }
        }
    }

    @Nested
    @DisplayName("при connection-pool.monitoring-enabled=false")
    @SpringBootTest(
            classes = TestConfig.class,
            properties = "platform.health.postgres.connection-pool.monitoring-enabled=false"
    )
    class OmitPoolMetricsWhenMonitoringDisabled {

        @DynamicPropertySource
        static void configureNested(DynamicPropertyRegistry registry) {
            PostgresHealthIndicatorIntegrationTest.configureProperties(registry);
            registry.add("platform.health.postgres.connection-pool.monitoring-enabled", () -> "false");
        }

        @Autowired
        private PostgresHealthIndicator postgresHealthIndicator;

        @Test
        @DisplayName("не включает ключ pool в details, статус UP")
        void shouldOmitPoolMetricsWhenMonitoringDisabled() {
            Health health = postgresHealthIndicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .doesNotContainKey("pool")
                    .containsKey("database")
                    .containsKey("durationMs");
        }
    }
}
