package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.*;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Стратегия обработки нативных jOOQ-исключений ({@code org.jooq.exception.DataAccessException})
 * как первопричины бизнес-исключений.
 *
 * <p>Все jOOQ-классы загружаются через reflection — стратегия компилируется и загружается
 * без наличия {@code org.jooq:jooq} на classpath. Условие активации обеспечивается
 * {@code @ConditionalOnClass(name = "org.jooq.exception.DataAccessException")} в auto-configuration.
 *
 * <h2>Мотивация</h2>
 * <p>При использовании jOOQ со Spring Boot существуют два пути обработки SQL-ошибок:
 * <ol>
 *   <li>{@code JooqExceptionTranslator} (из {@code spring-boot-starter-jooq}) транслирует
 *       {@code SQLException} в Spring {@code DataAccessException} — обрабатывается
 *       {@link DataAccessExceptionStrategy}.</li>
 *   <li>jOOQ-native исключения (маппинг, валидация результата, type conversion, optimistic locking
 *       на {@code UpdatableRecord}) бросаются как {@code org.jooq.exception.DataAccessException}
 *       (extends {@code RuntimeException}, <b>не</b> Spring {@code DataAccessException}) и
 *       не перехватываются ни одной существующей стратегией.</li>
 * </ol>
 * Без {@code spring-boot-starter-jooq} (или если {@code JooqExceptionTranslator} не зарегистрирован)
 * все jOOQ-исключения включая SQL-wrapping остаются необработанными.
 *
 * <h2>Обрабатываемые исключения</h2>
 * <pre>
 *   org.jooq.exception.DataAccessException (base, extends RuntimeException)
 *   │
 *   ├─ NoDataFoundException                    → 404  NO_DATA_FOUND             not retryable
 *   │   extends InvalidResultException
 *   │   fetchSingle()/fetchOne() вернул 0 строк.
 *   │
 *   ├─ TooManyRowsException                    → 500  TOO_MANY_ROWS             not retryable
 *   │   extends InvalidResultException
 *   │   fetchSingle()/fetchOne() вернул более 1 строки.
 *   │
 *   ├─ InvalidResultException                  → 500  INVALID_RESULT            not retryable
 *   │   Неожиданный результат запроса (generic).
 *   │
 *   ├─ IntegrityConstraintViolationException   → 409  INTEGRITY_VIOLATION       not retryable
 *   │   Нарушение constraint (unique, FK, CHECK).
 *   │   Enrichment: constraintName (PostgreSQL/MySQL/H2).
 *   │
 *   ├─ DataChangedException                    → 409  OPTIMISTIC_LOCK           retryable
 *   │   Optimistic locking на UpdatableRecord: данные изменились.
 *   │
 *   ├─ DataTypeException                       → 400  DATA_TYPE_ERROR           not retryable
 *   │   Ошибка преобразования типов в jOOQ type system.
 *   │
 *   ├─ DataException                           → 400  DATA_ERROR                not retryable
 *   │   Эквивалент JDBC SQLDataException.
 *   │
 *   ├─ MappingException                        → 500  MAPPING_ERROR             not retryable
 *   │   Ошибка маппинга POJO (fetchInto, into, from).
 *   │
 *   ├─ DetachedException                       → 500  DETACHED                  not retryable
 *   │   Операция на detached Record/Query без Configuration.
 *   │
 *   ├─ ConfigurationException                  → 500  CONFIGURATION_ERROR       not retryable
 *   │   DSL Configuration не позволяет выполнить операцию.
 *   │
 *   ├─ SQLDialectNotSupportedException         → 500  DIALECT_NOT_SUPPORTED     not retryable
 *   │   SQL-конструкция не поддерживается диалектом.
 *   │
 *   ├─ org.jooq.exception.IOException          → 503  IO_ERROR                  retryable
 *   │   Обёртка над java.io.IOException (NB: не java.io.IOException).
 *   │
 *   ├─ DataDefinitionException                 → 500  DDL_ERROR                 not retryable
 *   │   Ошибка DDL-интерпретации (DSLContext.meta()).
 *   │
 *   ├─ DataMigrationException                  → 500  MIGRATION_ERROR           not retryable
 *   │   │  Ошибка миграции. Покрывает DataMigrationVerificationException.
 *   │   └─ DataMigrationVerificationException
 *   │
 *   ├─ LoaderConfigurationException            → 500  LOADER_CONFIG_ERROR       not retryable
 *   │   Некорректная конфигурация Loader API.
 *   │
 *   ├─ TemplatingException                     → 500  TEMPLATE_ERROR            not retryable
 *   │   Ошибка plain SQL templating.
 *   │
 *   ├─ ParserException                         → 500  PARSER_ERROR              not retryable
 *   │   Ошибка парсинга SQL.
 *   │
 *   └─ (не загружаются отдельно, поглощаются GENERIC)
 *       ├─ MetaDataUnavailableException   — internal, крайне редко в production
 *       └─ MockFileDatabaseException      — тестовая инфраструктура
 * </pre>
 *
 * <h2>Разграничение с {@link DataAccessExceptionStrategy}</h2>
 * <p>{@code org.jooq.exception.DataAccessException} <b>не является</b> подтипом
 * {@code org.springframework.dao.DataAccessException}. Overlap невозможен.
 * Когда {@code JooqExceptionTranslator} активен, SQL-ошибки транслируются
 * в Spring {@code DataAccessException} на уровне ExecuteListener и обрабатываются
 * {@link DataAccessExceptionStrategy}. Если jOOQ exception достигает данной стратегии —
 * translator не сработал.
 *
 * <h2>{@code supportsCause()} — без исключений</h2>
 * <p>В отличие от {@link HibernateExceptionStrategy}, которая исключает
 * {@code JDBCException} (надёжно транслируется Spring через
 * {@code PersistenceExceptionTranslationPostProcessor}), jOOQ-трансляция зависит
 * от наличия {@code JooqExceptionTranslator}. Без него SQL-wrapping exceptions
 * должны быть перехвачены данной стратегией.
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <p>Поля {@code diagnosticMetadata} (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения ({@code getName()}).</li>
 *   <li>{@code causeMessage} — сообщение исключения (truncated до 300 символов).</li>
 *   <li>{@code stackHint} — сжатый стек-трейс из прикладных фреймов.</li>
 * </ul>
 *
 * <h3>Безопасность (CWE-209)</h3>
 * <p>{@code sqlState} и {@code sqlErrorCode} — protocol-level metadata.
 * {@code constraintName} — schema-level metadata. SQL-запросы, данные строк,
 * JDBC URL намеренно <b>не включаются</b>.</p>
 */
public class JooqExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;

    private static final ClassLoader LOADER = JooqExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> JOOQ_DATA_ACCESS_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.DataAccessException", LOADER);

    // Группа 1: Result Validation
    @Nullable
    private static final Class<?> NO_DATA_FOUND_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.NoDataFoundException", LOADER);

    @Nullable
    private static final Class<?> TOO_MANY_ROWS_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.TooManyRowsException", LOADER);

    @Nullable
    private static final Class<?> INVALID_RESULT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.InvalidResultException", LOADER);

    // Группа 2: Data Integrity
    @Nullable
    private static final Class<?> INTEGRITY_CONSTRAINT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.IntegrityConstraintViolationException", LOADER);

    @Nullable
    private static final Class<?> DATA_CHANGED_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.DataChangedException", LOADER);

    // Группа 3: Type / Mapping
    @Nullable
    private static final Class<?> DATA_TYPE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.DataTypeException", LOADER);

    @Nullable
    private static final Class<?> DATA_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.DataException", LOADER);

    @Nullable
    private static final Class<?> MAPPING_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.MappingException", LOADER);

    // Группа 4: Configuration (programming errors)
    @Nullable
    private static final Class<?> DETACHED_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.DetachedException", LOADER);

    @Nullable
    private static final Class<?> CONFIGURATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.ConfigurationException", LOADER);

    @Nullable
    private static final Class<?> DIALECT_NOT_SUPPORTED_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.SQLDialectNotSupportedException", LOADER);

    @Nullable
    private static final Class<?> LOADER_CONFIG_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.LoaderConfigurationException", LOADER);

    @Nullable
    private static final Class<?> TEMPLATING_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.TemplatingException", LOADER);

    @Nullable
    private static final Class<?> PARSER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.ParserException", LOADER);

    // Группа 5: Infrastructure
    @Nullable
    private static final Class<?> JOOQ_IO_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.IOException", LOADER);

    @Nullable
    private static final Class<?> DDL_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.DataDefinitionException", LOADER);

    // DataMigrationVerificationException поглощается через parent (оба → MIGRATION_ERROR)
    @Nullable
    private static final Class<?> MIGRATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.jooq.exception.DataMigrationException", LOADER);

    public JooqExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.JOOQ, stackHintExtractor);
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(JOOQ_DATA_ACCESS_EXCEPTION, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        JooqErrorType type = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("persistence")
                .diagnosticMetadata(buildDiagnosticMetadata(cause, type))
                .build();
        details.add(info);

        return type.toResolution(details);
    }

    @NonNull
    private static JooqErrorType classify(@NonNull Throwable cause) {
        // Группа 1: Result Validation
        if (ClassUtils.isInstanceOf(NO_DATA_FOUND_EXCEPTION, cause)) {
            return JooqErrorType.NO_DATA_FOUND;
        }
        if (ClassUtils.isInstanceOf(TOO_MANY_ROWS_EXCEPTION, cause)) {
            return JooqErrorType.TOO_MANY_ROWS;
        }
        if (ClassUtils.isInstanceOf(INVALID_RESULT_EXCEPTION, cause)) {
            return JooqErrorType.INVALID_RESULT;
        }

        // Группа 2: Data Integrity
        if (ClassUtils.isInstanceOf(INTEGRITY_CONSTRAINT_EXCEPTION, cause)) {
            return JooqErrorType.INTEGRITY_VIOLATION;
        }
        if (ClassUtils.isInstanceOf(DATA_CHANGED_EXCEPTION, cause)) {
            return JooqErrorType.OPTIMISTIC_LOCK;
        }

        // Группа 3: Type / Mapping
        if (ClassUtils.isInstanceOf(DATA_TYPE_EXCEPTION, cause)) {
            return JooqErrorType.DATA_TYPE_ERROR;
        }
        if (ClassUtils.isInstanceOf(DATA_EXCEPTION, cause)) {
            return JooqErrorType.DATA_ERROR;
        }
        if (ClassUtils.isInstanceOf(MAPPING_EXCEPTION, cause)) {
            return JooqErrorType.MAPPING_ERROR;
        }

        // Группа 4: Configuration (programming errors)
        if (ClassUtils.isInstanceOf(DETACHED_EXCEPTION, cause)) {
            return JooqErrorType.DETACHED;
        }
        if (ClassUtils.isInstanceOf(CONFIGURATION_EXCEPTION, cause)) {
            return JooqErrorType.CONFIGURATION_ERROR;
        }
        if (ClassUtils.isInstanceOf(DIALECT_NOT_SUPPORTED_EXCEPTION, cause)) {
            return JooqErrorType.DIALECT_NOT_SUPPORTED;
        }
        if (ClassUtils.isInstanceOf(LOADER_CONFIG_EXCEPTION, cause)) {
            return JooqErrorType.LOADER_CONFIG_ERROR;
        }
        if (ClassUtils.isInstanceOf(TEMPLATING_EXCEPTION, cause)) {
            return JooqErrorType.TEMPLATE_ERROR;
        }
        if (ClassUtils.isInstanceOf(PARSER_EXCEPTION, cause)) {
            return JooqErrorType.PARSER_ERROR;
        }

        // Группа 5: Infrastructure
        if (ClassUtils.isInstanceOf(JOOQ_IO_EXCEPTION, cause)) {
            return JooqErrorType.IO_ERROR;
        }
        if (ClassUtils.isInstanceOf(DDL_EXCEPTION, cause)) {
            return JooqErrorType.DDL_ERROR;
        }
        if (ClassUtils.isInstanceOf(MIGRATION_EXCEPTION, cause)) {
            return JooqErrorType.MIGRATION_ERROR;
        }

        return JooqErrorType.GENERIC;
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull JooqErrorType type) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        String message = cause.getMessage();
        builder.putTruncatedIfPresent("causeMessage", message, MAX_CAUSE_MESSAGE_LENGTH);

        enrichWithSqlDetails(cause, builder);

        if (type == JooqErrorType.INTEGRITY_VIOLATION) {
            String constraintName = ConstraintNameExtractor.extract(message);
            if (constraintName != null) {
                builder.put("constraintName", constraintName);
            }
        }

        return builder.withStackHint().build();
    }

    private static void enrichWithSqlDetails(@NonNull Throwable cause,
                                             @NonNull DiagnosticMetadataBuilder builder) {
        Throwable current = cause;

        for (int i = 0; (i < CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH) && (current != null); i++) {
            if (current instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if (sqlState != null && !sqlState.isBlank()) {
                    builder.put("sqlState", sqlState);
                }

                int errorCode = sqlEx.getErrorCode();
                if (errorCode != 0) {
                    builder.put("sqlErrorCode", String.valueOf(errorCode));
                }

                return;
            }

            current = current.getCause();
        }
    }

    private enum JooqErrorType implements ErrorClassification {

        // Result Validation

        NO_DATA_FOUND(
                HttpStatus.NOT_FOUND,
                Retryability.NOT_RETRYABLE,
                "The query returned no rows when exactly one was expected."
        ),

        TOO_MANY_ROWS(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The query returned more than one row when at most one was expected."
        ),

        INVALID_RESULT(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The query returned an unexpected result."
        ),

        // Data Integrity

        INTEGRITY_VIOLATION(
                HttpStatus.CONFLICT,
                Retryability.NOT_RETRYABLE,
                "The operation violates a data integrity constraint."
        ),

        OPTIMISTIC_LOCK(
                HttpStatus.CONFLICT,
                Retryability.RETRYABLE,
                "The record was modified by another transaction. "
                        + "Reload the record and retry the operation."
        ),

        // Type / Mapping

        DATA_TYPE_ERROR(
                HttpStatus.BAD_REQUEST,
                Retryability.NOT_RETRYABLE,
                "A data type conversion error occurred."
        ),

        DATA_ERROR(
                HttpStatus.BAD_REQUEST,
                Retryability.NOT_RETRYABLE,
                "The data provided is invalid or cannot be processed."
        ),

        MAPPING_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Failed to map the query result to a Java object. "
                        + "Verify the target class structure and field types."
        ),

        // Configuration

        DETACHED(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An operation was invoked on a detached query or record. "
                        + "Ensure the object is attached to a Configuration with a DataSource."
        ),

        CONFIGURATION_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The jOOQ configuration does not allow the requested operation."
        ),

        DIALECT_NOT_SUPPORTED(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The SQL construct is not supported by the configured SQL dialect."
        ),

        LOADER_CONFIG_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The data loader was invoked with an invalid configuration."
        ),

        TEMPLATE_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "A plain SQL templating error occurred. "
                        + "Verify the template syntax and parameter placeholders."
        ),

        PARSER_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Failed to parse the SQL statement."
        ),

        // Infrastructure

        IO_ERROR(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "An I/O error occurred during a database operation. Please retry later."
        ),

        DDL_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An error occurred while interpreting a DDL statement."
        ),

        /**
         * Покрывает {@code DataMigrationException} и {@code DataMigrationVerificationException}.
         */
        MIGRATION_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "A database migration error occurred."
        ),

        GENERIC(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An unexpected jOOQ data access error occurred."
        );

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        JooqErrorType(@NonNull HttpStatus httpStatus,
                      @NonNull Retryability retryability,
                      @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @NonNull
        public HttpStatus httpStatus() {
            return httpStatus;
        }

        @NonNull
        public Retryability retryability() {
            return retryability;
        }

        @NonNull
        public String message() {
            return message;
        }
    }
}
