package space.br1440.platform.errorhandling.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.PostgresExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * E2E-тест: реальные PostgreSQL-исключения через Testcontainers →
 * прямой вызов {@link PostgresExceptionStrategy} на исключении, извлечённом
 * из cause-chain Spring-обёртки {@link DuplicateKeyException}.
 *
 * <h2>Архитектура теста</h2>
 * <ul>
 *   <li>{@code @Testcontainers} + {@code @Container} static — контейнер стартует один раз
 *       на класс ({@link org.testcontainers.junit.jupiter.TestcontainersExtension}).</li>
 *   <li>{@code @SpringBootTest} с минимальной inner-конфигурацией ({@link TestConfig}) —
 *       поднимается {@link javax.sql.DataSource} (HikariCP) и {@link JdbcTemplate}
 *       через {@link DataSourceAutoConfiguration} + {@link JdbcTemplateAutoConfiguration}.</li>
 *   <li>{@code @DynamicPropertySource} прокидывает координаты контейнера
 *       в {@code spring.datasource.*} до старта Spring-контекста.</li>
 *   <li>Каждый тест получает свежую таблицу через {@link BeforeEach} → независимость тестов.</li>
 * </ul>
 *
 * <p>{@code disabledWithoutDocker = true} отключает класс до попытки старта контейнера,
 * если Docker-демон недоступен.
 */
@Tag("testcontainers")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PostgresStrategyE2ETest.TestConfig.class)
@DisplayName("PostgresExceptionStrategy — E2E с реальным PostgreSQL (Testcontainers + Spring)")
class PostgresStrategyE2ETest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgresExceptionStrategy strategy;

    @BeforeEach
    void recreateSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS e2e_users");
        jdbcTemplate.execute("""
                CREATE TABLE e2e_users (
                    id    SERIAL PRIMARY KEY,
                    email VARCHAR(255) NOT NULL UNIQUE
                )
                """);
    }

    @Nested
    @DisplayName("Проверка реального PSQLException (SQLState 23505)")
    class RealPsqlExceptionVerification {

        @Test
        @DisplayName("дублирование записи → PSQLException с SQLState 23505 и сообщением о duplicate key")
        void duplicateInsert_producesPsqlExceptionWithSqlState23505() {
            PSQLException caught = insertDuplicateAndCatch();

            assertThat((Throwable) caught)
                    .isInstanceOf(PSQLException.class);
            assertThat(caught.getSQLState())
                    .as("SQLState должен быть 23505 (unique_violation)")
                    .isEqualTo("23505");
            assertThat(caught.getMessage())
                    .containsIgnoringCase("duplicate key");
        }

        @Test
        @DisplayName("ServerErrorMessage содержит имя constraint и таблицу")
        void duplicateInsert_serverErrorMessageContainsConstraintAndTable() {
            PSQLException caught = insertDuplicateAndCatch();

            assertThat(caught.getServerErrorMessage())
                    .as("ServerErrorMessage не должен быть null для реального PSQLException")
                    .isNotNull();
            assertThat(caught.getServerErrorMessage().getTable())
                    .isEqualTo("e2e_users");
            assertThat(caught.getServerErrorMessage().getConstraint())
                    .as("имя constraint для UNIQUE email")
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("PostgresExceptionStrategy обрабатывает реальный PSQLException")
    class StrategyHandlesRealException {

        @Test
        @DisplayName("supportsCause() возвращает true для реального PSQLException")
        void supportsCause_returnsTrueForRealPsqlException() {
            PSQLException caught = insertDuplicateAndCatch();

            assertThat(strategy.supportsCause(caught))
                    .as("Стратегия должна распознать реальный PSQLException")
                    .isTrue();
        }

        @Test
        @DisplayName("extractCauseDetails() → httpStatus=409 CONFLICT для unique_violation")
        void extractCauseDetails_returnsConflictForUniqueViolation() {
            PSQLException caught = insertDuplicateAndCatch();

            RootCauseResolution resolution = strategy.extractCauseDetails(caught);

            assertThat(resolution.httpStatus())
                    .as("unique_violation (23505) → HTTP 409 CONFLICT")
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("extractCauseDetails() → diagnosticMetadata содержит pgSqlState=23505")
        void extractCauseDetails_diagnosticMetadataContainsSqlState() {
            PSQLException caught = insertDuplicateAndCatch();

            RootCauseResolution resolution = strategy.extractCauseDetails(caught);

            ErrorInfoDetail infoDetail = extractErrorInfoDetail(resolution);
            assertThat(infoDetail).isNotNull();

            assertThat(infoDetail.getDiagnosticMetadata())
                    .as("diagnosticMetadata должен содержать pgSqlState")
                    .containsEntry("pgSqlState", "23505");
        }

        @Test
        @DisplayName("extractCauseDetails() → diagnosticMetadata содержит pgTable и pgConstraint")
        void extractCauseDetails_diagnosticMetadataContainsTableAndConstraint() {
            PSQLException caught = insertDuplicateAndCatch();

            RootCauseResolution resolution = strategy.extractCauseDetails(caught);

            ErrorInfoDetail infoDetail = extractErrorInfoDetail(resolution);
            assertThat(infoDetail).isNotNull();

            assertThat(infoDetail.getDiagnosticMetadata())
                    .containsKey("pgTable")
                    .containsKey("pgConstraint");
            assertThat(infoDetail.getDiagnosticMetadata().get("pgTable"))
                    .isEqualTo("e2e_users");
        }

        @Test
        @DisplayName("extractCauseDetails() → clientMetadata содержит constraintType=UNIQUE")
        void extractCauseDetails_clientMetadataContainsConstraintType() {
            PSQLException caught = insertDuplicateAndCatch();

            RootCauseResolution resolution = strategy.extractCauseDetails(caught);

            ErrorInfoDetail infoDetail = extractErrorInfoDetail(resolution);
            assertThat(infoDetail).isNotNull();

            assertThat(infoDetail.getClientMetadata())
                    .containsEntry("constraintType", "UNIQUE");
        }

        @Test
        @DisplayName("extractCauseDetails() → reason=UNIQUE_VIOLATION, domain=postgresql")
        void extractCauseDetails_reasonAndDomainAreCorrect() {
            PSQLException caught = insertDuplicateAndCatch();

            RootCauseResolution resolution = strategy.extractCauseDetails(caught);

            ErrorInfoDetail infoDetail = extractErrorInfoDetail(resolution);
            assertThat(infoDetail).isNotNull();

            assertThat(infoDetail.getReason()).isEqualTo("UNIQUE_VIOLATION");
            assertThat(infoDetail.getDomain()).isEqualTo("postgresql");
        }

        @Test
        @DisplayName("extractCauseDetails() → message описывает unique-конфликт")
        void extractCauseDetails_messageDescribesUniqueConflict() {
            PSQLException caught = insertDuplicateAndCatch();

            RootCauseResolution resolution = strategy.extractCauseDetails(caught);

            assertThat(resolution.message())
                    .as("сообщение должно описывать дублирование ключа")
                    .containsIgnoringCase("unique key");
        }

        @Test
        @DisplayName("extractCauseDetails() → exceptionClass=org.postgresql.util.PSQLException в diagnosticMetadata")
        void extractCauseDetails_diagnosticMetadataContainsExceptionClass() {
            PSQLException caught = insertDuplicateAndCatch();

            RootCauseResolution resolution = strategy.extractCauseDetails(caught);

            ErrorInfoDetail infoDetail = extractErrorInfoDetail(resolution);
            assertThat(infoDetail).isNotNull();

            assertThat(infoDetail.getDiagnosticMetadata())
                    .containsEntry("exceptionClass", "org.postgresql.util.PSQLException");
        }
    }

    /**
     * Дублирующая вставка через {@link JdbcTemplate} → Spring заворачивает
     * нативный {@link PSQLException} в {@link DuplicateKeyException}; извлекаем
     * корневой {@link PSQLException} из cause-chain — это то, что увидит стратегия
     * в production-окружении после прохождения через {@code DataAccessException}-pipeline.
     */
    private PSQLException insertDuplicateAndCatch() {
        String uniqueEmail = "e2e-" + System.nanoTime() + "@test.com";
        jdbcTemplate.update("INSERT INTO e2e_users (email) VALUES (?)", uniqueEmail);

        Throwable thrown = catchThrowable(() ->
                jdbcTemplate.update("INSERT INTO e2e_users (email) VALUES (?)", uniqueEmail)
        );
        assertThat(thrown)
                .as("Ожидается DataAccessException при вставке дублирующей записи")
                .isInstanceOf(DuplicateKeyException.class);

        PSQLException psql = unwrapPsqlException(thrown);
        assertThat((Throwable) psql)
                .as("В cause-chain должен присутствовать PSQLException")
                .isNotNull();
        return psql;
    }

    private static PSQLException unwrapPsqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PSQLException psql) {
                return psql;
            }
            current = current.getCause();
        }
        return null;
    }

    private static ErrorInfoDetail extractErrorInfoDetail(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * Минимальная Spring Boot конфигурация для E2E-теста.
     *
     * <p>{@link DataSourceAutoConfiguration} и {@link JdbcTemplateAutoConfiguration}
     * автоматически создают {@code DataSource} (HikariCP) и {@link JdbcTemplate} из
     * {@code spring.datasource.*} properties, инжектируемых через {@link DynamicPropertySource}.
     *
     * <p>{@link PostgresExceptionStrategy} регистрируется как бин с production-семантикой:
     * {@code includeDetail=true}, реальный {@link StackHintExtractor}.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
    static class TestConfig {

        @Bean
        StackHintExtractor stackHintExtractor() {
            return new StackHintExtractor(false, List.of());
        }

        @Bean
        PostgresExceptionStrategy postgresExceptionStrategy(StackHintExtractor stackHintExtractor) {
            return new PostgresExceptionStrategy(true, stackHintExtractor);
        }
    }
}
