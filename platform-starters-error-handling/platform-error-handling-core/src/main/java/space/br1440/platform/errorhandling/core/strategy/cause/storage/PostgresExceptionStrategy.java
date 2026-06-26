package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Root-cause стратегия обработки PostgreSQL-исключений ({@code PSQLException}).
 *
 * <p>Перехватывает {@code org.postgresql.util.PSQLException} в cause-chain и обогащает
 * {@link RootCauseResolution} PostgreSQL-специфичной диагностикой, недоступной через generic
 * {@link DataAccessExceptionStrategy}.
 *
 * <h2>Архитектура</h2>
 * <p>{@code PSQLException} почти никогда не приходит напрямую в {@code GlobalExceptionHandler}.
 * Spring Data / Hibernate оборачивают её в {@code DataAccessException}:
 * <pre>
 *   DataIntegrityViolationException           (top-level)
 *     └── ConstraintViolationException        (Hibernate)
 *           └── PSQLException                 (root cause — перехватывается здесь)
 *                 └── ServerErrorMessage: sqlState=23505, table=users, constraint=uk_email
 * </pre>
 *
 * <h2>SQLSTATE-классификация</h2>
 * <p>Двухуровневый dispatch: точный match по 5-символьному SQLSTATE,
 * затем fallback по 2-символьному классу. Покрывает 16 типов ошибок:
 * integrity violations (23xxx), transaction rollback (40xxx),
 * operator intervention (57xxx), connection failures (08xxx),
 * insufficient resources (53xxx), permission denied (42501).
 *
 * <h2>Diagnostic metadata из ServerErrorMessage</h2>
 * <p>Извлекает 9 полей через reflection: {@code pgSqlState}, {@code pgConstraint},
 * {@code pgTable}, {@code pgSchema}, {@code pgColumn}, {@code pgDatatype},
 * {@code pgDetail} (sanitized), {@code pgHint}, {@code pgRoutine}.
 * Все поля — только в {@code diagnosticMetadata} (SRE-only).
 *
 * <h2>Безопасность (CWE-209 / CWE-312)</h2>
 * <p>{@code pgDetail} содержит значения данных (e.g.,
 * {@code Key (email)=(user@example.com) already exists}) — sanitized через regex
 * strip значений. {@code pgDetail} и {@code pgHint} включаются только в
 * {@code diagnosticMetadata}, никогда в HTTP-ответ клиенту.
 */
public class PostgresExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;
    private static final int MAX_DETAIL_LENGTH = 200;

    private static final ClassLoader LOADER = PostgresExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> PSQL_EXCEPTION = ReflectionUtils.loadOptionalClass("org.postgresql.util.PSQLException", LOADER);

    private static final Pattern DETAIL_VALUE_PATTERN = Pattern.compile("=\\([^)]*\\)");

    private static final Map<String, PostgresErrorType> EXACT_SQLSTATE_MAP;
    private static final Map<String, PostgresErrorType> CLASS_SQLSTATE_MAP;
    static {
        Map<String, PostgresErrorType> exact = new HashMap<>();
        exact.put("23505", PostgresErrorType.UNIQUE_VIOLATION);
        exact.put("23503", PostgresErrorType.FOREIGN_KEY_VIOLATION);
        exact.put("23502", PostgresErrorType.NOT_NULL_VIOLATION);
        exact.put("23514", PostgresErrorType.CHECK_VIOLATION);
        exact.put("23P01", PostgresErrorType.EXCLUSION_VIOLATION);
        exact.put("23001", PostgresErrorType.RESTRICT_VIOLATION);
        exact.put("40001", PostgresErrorType.SERIALIZATION_FAILURE);
        exact.put("40P01", PostgresErrorType.DEADLOCK_DETECTED);
        exact.put("57014", PostgresErrorType.QUERY_CANCELED);
        exact.put("55P03", PostgresErrorType.LOCK_NOT_AVAILABLE);
        exact.put("25P03", PostgresErrorType.IDLE_SESSION_TIMEOUT);
        exact.put("42501", PostgresErrorType.PERMISSION_DENIED);
        EXACT_SQLSTATE_MAP = Map.copyOf(exact);

        Map<String, PostgresErrorType> byClass = new HashMap<>();
        byClass.put("08", PostgresErrorType.CONNECTION_FAILURE);
        byClass.put("23", PostgresErrorType.INTEGRITY_VIOLATION);
        byClass.put("40", PostgresErrorType.TRANSACTION_ROLLBACK);
        byClass.put("42", PostgresErrorType.SYNTAX_OR_ACCESS);
        byClass.put("53", PostgresErrorType.INSUFFICIENT_RESOURCES);
        byClass.put("57", PostgresErrorType.OPERATOR_INTERVENTION);
        CLASS_SQLSTATE_MAP = Map.copyOf(byClass);
    }

    private final boolean includeDetail;

    public PostgresExceptionStrategy(boolean includeDetail, StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.POSTGRES, stackHintExtractor);
        this.includeDetail = includeDetail;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(PSQL_EXCEPTION, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        Object serverErrorMessage = extractServerErrorMessage(cause);
        if (serverErrorMessage == null) {
            return handleNoServerErrorMessage(cause);
        }

        String sqlState = extractStringField(serverErrorMessage, "getSQLState");
        PostgresErrorType type = classifyBySqlState(sqlState);

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryability == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        Map<String, String> clientMeta = new LinkedHashMap<>();
        clientMeta.put("constraintType", type.constraintType);

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("postgresql")
                .clientMetadata(clientMeta)
                .diagnosticMetadata(buildDiagnosticMetadata(cause, serverErrorMessage, sqlState, includeDetail))
                .build();
        details.add(info);

        return type.toResolution(details);
    }

    @NonNull
    private RootCauseResolution handleNoServerErrorMessage(@NonNull Throwable cause) {
        PostgresErrorType type = classifyByMessage(cause.getMessage());

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryability == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        Map<String, String> diagnosticMeta = new LinkedHashMap<>();
        diagnosticMeta.put("exceptionClass", cause.getClass().getName());
        diagnosticMeta.put("serverErrorMessage", "null");

        String message = cause.getMessage();
        if (StringUtils.hasText(message)) {
            diagnosticMeta.put("causeMessage", StringUtils.truncate(message, MAX_CAUSE_MESSAGE_LENGTH));
        }

        String stackHint = stackHintExtractor.extractStackHint(cause);
        if (StringUtils.hasText(stackHint)) {
            diagnosticMeta.put("stackHint", stackHint);
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("postgresql")
                .diagnosticMetadata(Collections.unmodifiableMap(diagnosticMeta))
                .build();
        details.add(info);

        return RootCauseResolution.of(type.httpStatus, type.message, details, type.retryability);
    }

    @NonNull
    private static PostgresErrorType classifyByMessage(@Nullable String message) {
        if (message == null) {
            return PostgresErrorType.CONNECTION_FAILURE;
        }

        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("no more connections") || lower.contains("too many clients")) {
            return PostgresErrorType.INSUFFICIENT_RESOURCES;
        }

        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("query_wait_timeout")) {
            return PostgresErrorType.QUERY_CANCELED;
        }

        return PostgresErrorType.CONNECTION_FAILURE;
    }

    @NonNull
    private static PostgresErrorType classifyBySqlState(@Nullable String sqlState) {
        if (sqlState == null || sqlState.length() < 2) {
            return PostgresErrorType.GENERIC;
        }

        PostgresErrorType exact = EXACT_SQLSTATE_MAP.get(sqlState);
        if (exact != null) {
            return exact;
        }

        PostgresErrorType byClass = CLASS_SQLSTATE_MAP.get(sqlState.substring(0, 2));
        if (byClass != null) {
            return byClass;
        }

        return PostgresErrorType.GENERIC;
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull Object serverErrorMessage,
                                                        @Nullable String sqlState,
                                                        boolean includeDetail) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        if (StringUtils.hasText(sqlState)) {
            builder.put("pgSqlState", sqlState);
        }

        extractAndPut(serverErrorMessage, "getConstraint", "pgConstraint", builder);
        extractAndPut(serverErrorMessage, "getTable", "pgTable", builder);
        extractAndPut(serverErrorMessage, "getSchema", "pgSchema", builder);
        extractAndPut(serverErrorMessage, "getColumn", "pgColumn", builder);
        extractAndPut(serverErrorMessage, "getDatatype", "pgDatatype", builder);
        extractAndPut(serverErrorMessage, "getRoutine", "pgRoutine", builder);

        if (includeDetail) {
            String detail = extractStringField(serverErrorMessage, "getDetail");
            if (StringUtils.hasText(detail)) {
                builder.put("pgDetail", sanitizeDetail(detail));
            }
        }

        String hint = extractStringField(serverErrorMessage, "getHint");
        builder.putTruncatedIfPresent("pgHint", hint, MAX_DETAIL_LENGTH);

        String causeMessage = cause.getMessage();
        builder.putTruncatedIfPresent("causeMessage", causeMessage, MAX_CAUSE_MESSAGE_LENGTH);

        return builder.withStackHint().build();
    }

    /**
     * Удаляет значения данных из PostgreSQL DETAIL-сообщений для предотвращения утечки данных (CWE-312).
     * Например: {@code Key (email)=(user@example.com) already exists}
     * становится {@code Key (email)=(***) already exists}.
     */
    @NonNull
    private static String sanitizeDetail(@NonNull String detail) {
        String sanitized = DETAIL_VALUE_PATTERN.matcher(detail).replaceAll("=(***)");
        return Objects.requireNonNull(StringUtils.truncate(sanitized, MAX_DETAIL_LENGTH));
    }

    @Nullable
    private static Object extractServerErrorMessage(@NonNull Throwable cause) {
        final Object[] holder = {null};
        ReflectionUtils.invokeMethod(cause, "getServerErrorMessage", value -> holder[0] = value);
        return holder[0];
    }

    @Nullable
    private static String extractStringField(@NonNull Object target, @NonNull String methodName) {
        final String[] holder = {null};
        ReflectionUtils.invokeMethod(target, methodName, value -> {
            if (value instanceof String s && StringUtils.hasText(s)) {
                holder[0] = s;
            }
        });

        return holder[0];
    }

    private static void extractAndPut(@NonNull Object target, @NonNull String methodName,
                                      @NonNull String metadataKey,
                                      @NonNull DiagnosticMetadataBuilder builder) {
        String value = extractStringField(target, methodName);
        if (value != null) {
            builder.put(metadataKey, value);
        }
    }

    //@SuppressWarnings("java:S6548")
    private enum PostgresErrorType implements ErrorClassification {

        // Уровень 1 — точный match по SQLSTATE (5 символов)

        /** 23505: дублирование unique/primary key. */
        UNIQUE_VIOLATION(HttpStatus.CONFLICT, Retryability.NOT_RETRYABLE,
                "UNIQUE",
                "A record with the same unique key already exists."),

        /** 23503: нарушение внешнего ключа. */
        FOREIGN_KEY_VIOLATION(HttpStatus.CONFLICT, Retryability.NOT_RETRYABLE,
                "FOREIGN_KEY",
                "The operation violates a foreign key constraint."),

        /** 23502: отсутствует обязательное поле (NOT NULL). */
        NOT_NULL_VIOLATION(HttpStatus.BAD_REQUEST, Retryability.NOT_RETRYABLE,
                "NOT_NULL",
                "A required field is missing or null."),

        /** 23514: нарушение CHECK constraint. */
        CHECK_VIOLATION(HttpStatus.BAD_REQUEST, Retryability.NOT_RETRYABLE,
                "CHECK",
                "The data violates a check constraint."),

        /** 23P01: нарушение exclusion constraint (range-типы, temporal). */
        EXCLUSION_VIOLATION(HttpStatus.CONFLICT, Retryability.NOT_RETRYABLE,
                "EXCLUSION",
                "The operation violates an exclusion constraint."),

        /** 23001: нарушение RESTRICT rule. */
        RESTRICT_VIOLATION(HttpStatus.CONFLICT, Retryability.NOT_RETRYABLE,
                "RESTRICT",
                "The operation is restricted by a database rule."),

        /** 40001: конфликт serializable isolation level; транзакцию следует повторить. */
        SERIALIZATION_FAILURE(HttpStatus.CONFLICT, Retryability.RETRYABLE,
                "SERIALIZATION",
                "A concurrent transaction conflict occurred. Retry the operation."),

        /** 40P01: PostgreSQL автоматически прервал одну из транзакций для разрешения дедлока. */
        DEADLOCK_DETECTED(HttpStatus.CONFLICT, Retryability.RETRYABLE,
                "DEADLOCK",
                "A deadlock was detected. The transaction has been rolled back."),

        /** 57014: запрос отменён (statement_timeout или pg_cancel_backend). */
        QUERY_CANCELED(HttpStatus.GATEWAY_TIMEOUT, Retryability.RETRYABLE,
                "TIMEOUT",
                "The database query was canceled due to a timeout."),

        /** 55P03: блокировка недоступна (lock_timeout / NOWAIT); транзакцию следует повторить. */
        LOCK_NOT_AVAILABLE(HttpStatus.CONFLICT, Retryability.RETRYABLE,
                "LOCK",
                "The requested lock could not be acquired. Retry the operation."),

        /** 25P03: сессия завершена из-за idle_in_transaction_session_timeout. */
        IDLE_SESSION_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, Retryability.RETRYABLE,
                "TIMEOUT",
                "The database session was terminated due to idle timeout."),

        /** 42501: недостаточно прав для выполнения операции над объектом БД. */
        PERMISSION_DENIED(HttpStatus.FORBIDDEN, Retryability.NOT_RETRYABLE,
                "PERMISSION",
                "Insufficient database permissions to execute the operation."),

        // Уровень 2 — fallback по классу SQLSTATE (2 символа)

        /** 08xxx: потеря соединения, ошибка протокола или недоступность сервера. */
        CONNECTION_FAILURE(HttpStatus.SERVICE_UNAVAILABLE, Retryability.RETRYABLE,
                "CONNECTION",
                "The database connection failed or was lost."),

        /** 23xxx (прочие): нарушение integrity constraint, не покрытое точным match. */
        INTEGRITY_VIOLATION(HttpStatus.CONFLICT, Retryability.NOT_RETRYABLE,
                "INTEGRITY",
                "The operation violates a database integrity constraint."),

        /** 40xxx (прочие): откат транзакции по причинам, не покрытым точным match. */
        TRANSACTION_ROLLBACK(HttpStatus.SERVICE_UNAVAILABLE, Retryability.RETRYABLE,
                "TRANSACTION",
                "The database transaction was rolled back."),

        /** 42xxx (прочие): синтаксическая ошибка SQL или несоответствие схемы — баг в коде. */
        SYNTAX_OR_ACCESS(HttpStatus.INTERNAL_SERVER_ERROR, Retryability.NOT_RETRYABLE,
                "SYNTAX",
                "A database query syntax error or schema mismatch occurred."),

        /** 53xxx: нехватка ресурсов (disk full 53100, OOM 53200, too many connections 53300). */
        INSUFFICIENT_RESOURCES(HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                "RESOURCE",
                "The database server has insufficient resources to process the request."),

        /** 57xxx (прочие): вмешательство оператора (pg_terminate_backend и подобные). */
        OPERATOR_INTERVENTION(HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                "OPERATOR",
                "The database server is undergoing operator intervention."),

        /** Все прочие SQLSTATE, не покрытые ни точным match, ни классовым fallback. */
        GENERIC(HttpStatus.INTERNAL_SERVER_ERROR, Retryability.NOT_RETRYABLE,
                "UNKNOWN",
                "An unexpected PostgreSQL error occurred.");

        final HttpStatus httpStatus;
        final Retryability retryability;
        final String constraintType;
        final String message;

        PostgresErrorType(HttpStatus httpStatus,
                          Retryability retryability,
                          String constraintType,
                          String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.constraintType = constraintType;
            this.message = message;
        }

        @NonNull
        @Override
        public HttpStatus httpStatus() {
            return httpStatus;
        }

        @NonNull
        @Override
        public Retryability retryability() {
            return retryability;
        }

        @NonNull
        @Override
        public String message() {
            return message;
        }
    }
}
