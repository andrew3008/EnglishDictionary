package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.jetbrains.annotations.NotNull;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.strategy.top.BusinessExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.*;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Стратегия обработки исключений слоя данных (Spring Data / JDBC / JPA).
 * Активируется только при наличии {@code spring-tx} на classpath ({@code @ConditionalOnClass} в конфигурации).
 *
 * <p>Реализует {@link RootCauseExceptionHandlingStrategy}, позволяя {@link BusinessExceptionStrategy}
 * определять HTTP-статус платформенных исключений по их технической причине
 * (например, {@code DataIntegrityViolationException} → 409 Conflict).
 *
 * <h2>Диагностические метаданные</h2>
 * <p>Поля {@code diagnosticMetadata} (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения ({@code getName()}).</li>
 *   <li>{@code causeMessage} — сообщение исключения (truncated до 300 символов).</li>
 *   <li>{@code sqlState} — SQL State код из {@code SQLException} в cause-chain (глубина до 5).</li>
 *   <li>{@code sqlErrorCode} — vendor-specific error code ({@code 0} не включается).</li>
 *   <li>{@code constraintName} — для {@code DUPLICATE_KEY}/{@code INTEGRITY_VIOLATION}
 *       (PostgreSQL, MySQL, H2 форматы).</li>
 *   <li>{@code expectedSize}/{@code actualSize} — для {@code INCORRECT_RESULT_SIZE}
 *       ({@code actualSize} не включается при значении {@code -1}).</li>
 *   <li>{@code stackHint} — сжатый стек-трейс из прикладных фреймов.</li>
 * </ul>
 *
 * <h3>Безопасность (CWE-209)</h3>
 * <p>{@code sqlState}, {@code sqlErrorCode} и {@code constraintName} — schema-level / protocol-level
 * метаданные, не содержащие пользовательских данных. Включаются только в {@code diagnosticMetadata},
 * которые <b>не экспонируются</b> в клиентский ответ. SQL-запросы и данные строк намеренно
 * не включаются.</p>
 */
public class DataAccessExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;

    private static final ClassLoader LOADER = DataAccessExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> CASSANDRA_DRIVER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("com.datastax.oss.driver.api.core.DriverException", LOADER);

    @Nullable
    private static final Class<?> LETTUCE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("io.lettuce.core.RedisException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_EXCEPTION =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisException", LOADER);

    /**
     * ClickHouse client v2 base. Включён в KNOWN_DRIVER_EXCEPTIONS, чтобы
     * {@code UncategorizedDataAccessException} (обёртка от Spring SQL translator
     * над {@code SQLException}, поскольку SQL-state коды драйвера —
     * {@code 22000}, {@code 08000}, {@code HY000} — не описаны в
     * платформенном {@code error-codes.xml}) не «съедал» классификацию.
     * Делегирование уходит в {@link ClickHouseExceptionStrategy}.
     */
    @Nullable
    private static final Class<?> CLICKHOUSE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("com.clickhouse.client.api.ClickHouseException", LOADER);

    private static final Class<?>[] KNOWN_DRIVER_EXCEPTIONS = Stream.of(
                    CASSANDRA_DRIVER_EXCEPTION,
                    LETTUCE_EXCEPTION,
                    JEDIS_EXCEPTION,
                    CLICKHOUSE_EXCEPTION
            )
            .filter(Objects::nonNull)
            .toArray(Class<?>[]::new);

    public DataAccessExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.DATA_ACCESS, stackHintExtractor);
    }

    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        if (exception instanceof TransientDataAccessException
                || exception instanceof RecoverableDataAccessException) {
            return LogPolicy.WARN; // transient — не ERROR, просто предупреждение
        }

        return errorEntry.getHttpStatus().is5xxServerError() ? LogPolicy.ERROR : LogPolicy.WARN;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        if (!(cause instanceof DataAccessException)) {
            return false;
        }

        // UncategorizedDataAccessException — обёртка не транслированного driver exception.
        // Если внутри — известный драйверный тип, делегируем специфической стратегии.
        if (cause instanceof UncategorizedDataAccessException) {
            Throwable nested = cause.getCause();
            return (nested == null) || !isKnownDriverException(nested);
        }

        return true;
    }

    private static boolean isKnownDriverException(@NonNull Throwable cause) {
        for (Class<?> driverClass : KNOWN_DRIVER_EXCEPTIONS) {
            if (driverClass.isInstance(cause)) {
                return true;
            }
        }

        return false;
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        DataAccessExceptionType type = classify((DataAccessException) cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (isRetryable(type)) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("persistence")
                .diagnosticMetadata(buildDiagnosticMetadata((DataAccessException) cause, type))
                .build();
        details.add(info);

        HttpStatus httpStatus = mapToHttpStatus(type);
        String message = resolveMessage(type);
        Retryability retryability = isRetryable(type) ? Retryability.RETRYABLE : Retryability.NOT_RETRYABLE;

        return RootCauseResolution.of(httpStatus, message, details, retryability);
    }

    /**
     * Классифицирует {@link DataAccessException} в один из {@link DataAccessExceptionType}.
     * <p><b>Порядок case-веток критичен</b> из-за наследования в Spring DAO
     */
    @NonNull
    @SuppressWarnings("deprecation")
    private static DataAccessExceptionType classify(@NonNull DataAccessException exception) {
        return switch (exception) {
            case DeadlockLoserDataAccessException ignored -> DataAccessExceptionType.DEADLOCK;
            case CannotSerializeTransactionException ignored -> DataAccessExceptionType.SERIALIZATION_FAILURE;
            case CannotAcquireLockException ignored -> DataAccessExceptionType.LOCK_TIMEOUT;
            case PessimisticLockingFailureException ignored -> DataAccessExceptionType.PESSIMISTIC_LOCK;
            case OptimisticLockingFailureException ignored -> DataAccessExceptionType.OPTIMISTIC_LOCK;
            case DuplicateKeyException ignored -> DataAccessExceptionType.DUPLICATE_KEY;
            case DataIntegrityViolationException ignored -> DataAccessExceptionType.INTEGRITY_VIOLATION;
            case QueryTimeoutException ignored -> DataAccessExceptionType.QUERY_TIMEOUT;
            case TransientDataAccessResourceException ignored -> DataAccessExceptionType.TRANSIENT_FAILURE;
            case EmptyResultDataAccessException ignored -> DataAccessExceptionType.NOT_FOUND;

            case IncorrectResultSizeDataAccessException e ->
                    (e.getActualSize() == 0)
                            ? DataAccessExceptionType.NOT_FOUND
                            : DataAccessExceptionType.INCORRECT_RESULT_SIZE;

            case PermissionDeniedDataAccessException ignored -> DataAccessExceptionType.PERMISSION_DENIED;
            case DataAccessResourceFailureException ignored -> DataAccessExceptionType.RESOURCE_FAILURE;
            case RecoverableDataAccessException ignored -> DataAccessExceptionType.RECOVERABLE;
            case TypeMismatchDataAccessException ignored -> DataAccessExceptionType.TYPE_MISMATCH;
            case InvalidDataAccessApiUsageException ignored -> DataAccessExceptionType.INVALID_USAGE;
            case InvalidDataAccessResourceUsageException ignored -> DataAccessExceptionType.INVALID_QUERY;
            case CleanupFailureDataAccessException ignored -> DataAccessExceptionType.CLEANUP_FAILURE;
            default -> DataAccessExceptionType.GENERIC;
        };
    }

    @NonNull
    private static HttpStatus mapToHttpStatus(@NonNull DataAccessExceptionType type) {
        return switch (type) {
            case DUPLICATE_KEY, INTEGRITY_VIOLATION, OPTIMISTIC_LOCK, PESSIMISTIC_LOCK,
                 LOCK_TIMEOUT, DEADLOCK, SERIALIZATION_FAILURE, INCORRECT_RESULT_SIZE -> HttpStatus.CONFLICT; // 409

            case QUERY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT; // 504

            case TRANSIENT_FAILURE, RECOVERABLE, RESOURCE_FAILURE, CLEANUP_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE; // 503

            case NOT_FOUND -> HttpStatus.NOT_FOUND; // 404

            case PERMISSION_DENIED, TYPE_MISMATCH, INVALID_USAGE, INVALID_QUERY, GENERIC -> HttpStatus.INTERNAL_SERVER_ERROR; // 500
        };
    }

    private static boolean isRetryable(@NonNull DataAccessExceptionType type) {
        return switch (type) {
            case OPTIMISTIC_LOCK, PESSIMISTIC_LOCK, DEADLOCK, LOCK_TIMEOUT, SERIALIZATION_FAILURE,
                 QUERY_TIMEOUT, TRANSIENT_FAILURE, RECOVERABLE, RESOURCE_FAILURE -> true;

            case DUPLICATE_KEY, INTEGRITY_VIOLATION, NOT_FOUND, INCORRECT_RESULT_SIZE,
                 PERMISSION_DENIED, TYPE_MISMATCH, INVALID_USAGE, INVALID_QUERY,
                 CLEANUP_FAILURE, GENERIC -> false;
        };
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull DataAccessException exception,
                                                        @NonNull DataAccessExceptionType type) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(exception, stackHintExtractor);

        String message = exception.getMessage();
        builder.putTruncatedIfPresent("causeMessage", message, MAX_CAUSE_MESSAGE_LENGTH);

        if (exception instanceof IncorrectResultSizeDataAccessException e) {
            builder.put("expectedSize", String.valueOf(e.getExpectedSize()));
            if (e.getActualSize() >= 0) {
                builder.put("actualSize", String.valueOf(e.getActualSize()));
            }
        }

        enrichWithSqlDetails(exception, builder);

        if (type == DataAccessExceptionType.DUPLICATE_KEY || type == DataAccessExceptionType.INTEGRITY_VIOLATION) {
            String constraintName = ConstraintNameExtractor.extract(message);
            if (constraintName != null) {
                builder.put("constraintName", constraintName);
            }
        }

        return builder.withStackHint().build();
    }

    private static void enrichWithSqlDetails(@NonNull DataAccessException exception,
                                             @NonNull DiagnosticMetadataBuilder builder) {
        SQLException sqlEx = CauseChainNavigator.findSqlException(exception);
        if (sqlEx == null) {
            return;
        }

        String sqlState = sqlEx.getSQLState();
        if (sqlState != null && !sqlState.isBlank()) {
            builder.put("sqlState", sqlState);
        }

        int errorCode = sqlEx.getErrorCode();
        if (errorCode != 0) {
            builder.put("sqlErrorCode", String.valueOf(errorCode));
        }
    }

    @NonNull
    private static String resolveMessage(@NonNull DataAccessExceptionType type) {
        return switch (type) {
            case DUPLICATE_KEY -> "A record with the same unique identifier already exists";
            case INTEGRITY_VIOLATION -> "The operation violates a data integrity constraint";
            case OPTIMISTIC_LOCK -> "The resource was modified by another request. Please retry";
            case PESSIMISTIC_LOCK -> "The resource is currently locked by another operation. Please retry";
            case LOCK_TIMEOUT -> "Cannot acquire database lock due to a conflicting concurrent operation";
            case DEADLOCK -> "A database deadlock occurred. Please retry the operation";
            case SERIALIZATION_FAILURE -> "Transaction serialization conflict occurred. Please retry";
            case QUERY_TIMEOUT -> "Database query exceeded the configured timeout";
            case TRANSIENT_FAILURE -> "A temporary database resource failure occurred. Please retry";
            case RECOVERABLE -> "A recoverable database error occurred. Please retry the operation";
            case RESOURCE_FAILURE -> "Database is currently unavailable. Please retry later";
            case NOT_FOUND -> "The requested record does not exist";
            case INCORRECT_RESULT_SIZE -> "The query returned an unexpected number of results";
            case PERMISSION_DENIED -> "Insufficient database privileges for the requested operation";
            case TYPE_MISMATCH -> "Data type mismatch occurred during database operation";
            case INVALID_USAGE -> "Invalid usage of data access API";
            case INVALID_QUERY -> "Invalid query or SQL statement";
            case CLEANUP_FAILURE -> "Database cleanup operation failed";
            case GENERIC -> "An unexpected database error occurred";
        };
    }

    private enum DataAccessExceptionType {
        /**
         * E11000 duplicate key — нарушение уникального индекса.
         */
        DUPLICATE_KEY,
        /**
         * Нарушение целостности данных (constraint violation).
         */
        INTEGRITY_VIOLATION,
        /**
         * Оптимистическая блокировка — версия изменилась.
         */
        OPTIMISTIC_LOCK,
        /**
         * Пессимистическая блокировка — ресурс заблокирован.
         */
        PESSIMISTIC_LOCK,
        /**
         * Не удалось получить блокировку (lock timeout).
         */
        LOCK_TIMEOUT,
        /**
         * Deadlock — проигравшая сторона транзакции.
         */
        DEADLOCK,
        /**
         * Serialization failure при SERIALIZABLE isolation level.
         */
        SERIALIZATION_FAILURE,
        /**
         * Превышен таймаут запроса.
         */
        QUERY_TIMEOUT,
        /**
         * Временный сбой ресурса БД (transient).
         */
        TRANSIENT_FAILURE,
        /**
         * Восстановимая ошибка БД.
         */
        RECOVERABLE,
        /**
         * БД недоступна (transient resource failure, retryable).
         */
        RESOURCE_FAILURE,
        /**
         * Запись не найдена.
         */
        NOT_FOUND,
        /**
         * Неверное количество результатов (expected=1, got=N).
         */
        INCORRECT_RESULT_SIZE,
        /**
         * Недостаточно прав доступа к БД (ошибка конфигурации приложения).
         */
        PERMISSION_DENIED,
        /**
         * Несоответствие типов данных.
         */
        TYPE_MISMATCH,
        /**
         * Неверное использование API доступа к данным.
         */
        INVALID_USAGE,
        /**
         * Неверный SQL-запрос.
         */
        INVALID_QUERY,
        /**
         * Ошибка очистки ресурсов БД.
         */
        CLEANUP_FAILURE,
        /**
         * Прочие ошибки доступа к данным без специфической классификации.
         */
        GENERIC
    }
}
