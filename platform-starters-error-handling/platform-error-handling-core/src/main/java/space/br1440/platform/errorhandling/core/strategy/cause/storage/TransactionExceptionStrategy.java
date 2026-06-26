package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.transaction.*;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.core.util.CauseMetadataEnricher;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Стратегия обработки транзакционных исключений как первопричины бизнес-исключений.
 *
 * <p>Принципиально отличается от {@link ConcurrencyExceptionStrategy}, которая
 * обрабатывает конфликты блокировок и версий (<b>семантика данных</b>).
 * Данная стратегия обрабатывает ошибки <b>инфраструктуры TX-менеджера</b>:
 * невозможность создать транзакцию, неожиданный rollback, timeout транзакции,
 * heuristic outcomes в JTA/XA.
 *
 * <h2>Обрабатываемые исключения</h2>
 * <pre>
 * CannotCreateTransactionException                → 503  TX_CANNOT_CREATE           retryable
 *   TX-менеджер не может начать транзакцию: JNDI lookup failure,
 *   connection acquisition failure, конфигурация DataSource.
 *   В Kubernetes: DB-сервис недоступен после rolling update.
 *
 * TransactionTimedOutException                    → 504  TX_TIMEOUT                 retryable
 *   Транзакция превысила настроенный timeout. Отличается от
 *   query timeout ({@link DataAccessExceptionStrategy}) — здесь
 *   timeout на уровне всей транзакции, а не отдельного SQL-запроса.
 *
 * UnexpectedRollbackException                     → 409  TX_UNEXPECTED_ROLLBACK     retryable
 *   Транзакция откачена TX-менеджером, хотя бизнес-логика
 *   ожидала commit. Причины: rollback-only marking во вложенной
 *   транзакции, timeout, ошибка в @Transactional propagation.
 *
 * TransactionSystemException                      → 500  TX_SYSTEM_ERROR            not retryable
 *   Generic обёртка над непредвиденными ошибками TX-инфраструктуры.
 *   Может оборачивать: JPA PersistenceException, JTA SystemException,
 *   constraint violation при flush перед commit.
 *
 * HeuristicCompletionException                    → 500  TX_HEURISTIC_*             not retryable
 *   JTA/XA: heuristic outcome при двухфазном коммите.
 *   Данные могут быть в несогласованном состоянии между
 *   ресурсами (DB1 committed, DB2 rolled back).
 *   Подтипы: COMMITTED, ROLLED_BACK, MIXED, UNKNOWN.
 *
 * IllegalTransactionStateException                → 500  TX_ILLEGAL_STATE           not retryable
 *   Программная ошибка: попытка операции в недопустимом
 *   состоянии транзакции (commit без begin, nested в MANDATORY).
 * </pre>
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <p>Поля {@code diagnosticMetadata} (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения ({@code getName()}).</li>
 *   <li>{@code transactionLayer} — {@code LOCAL|JPA|XA}.</li>
 *   <li>{@code causeMessage} — сообщение исключения (truncated до 300 символов).</li>
 *   <li>{@code rootCauseClass}, {@code rootCauseMessage} — глубинная причина (если отличается).</li>
 *   <li>{@code stackHint} — сжатый стек-трейс из прикладных фреймов.</li>
 * </ul>
 *
 * <h3>Безопасность (CWE-209)</h3>
 * <p>{@code causeMessage} truncated до 300 символов, может содержать
 * JNDI-имена DataSource, SQL-constraint names, JTA resource identifiers
 * — допустимо для SRE-only {@code diagnosticMetadata}.
 * В клиентский ответ передаётся только безопасное {@code message()} из enum.</p>
 */
public class TransactionExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;

    private static final ClassLoader LOADER = TransactionExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> JPA_ROLLBACK_EXCEPTION =
            ReflectionUtils.loadOptionalClass("jakarta.persistence.RollbackException", LOADER);

    @Nullable
    private static final Class<?> XA_EXCEPTION =
            ReflectionUtils.loadOptionalClass("javax.transaction.xa.XAException", LOADER);

    @Nullable
    private static final Class<?> JAKARTA_XA_EXCEPTION =
            ReflectionUtils.loadOptionalClass("jakarta.transaction.xa.XAException", LOADER);

    public TransactionExceptionStrategy(@NonNull StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.TRANSACTION, stackHintExtractor);
    }

    /**
     * <p>Транзакционные ошибки → {@link LogPolicy#ERROR}.
     * Каждая ошибка TX-менеджера — потенциальное нарушение целостности данных.
     * {@code HeuristicCompletionException} особенно критична:
     * данные могут быть в несогласованном состоянии между ресурсами XA.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.ERROR;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return (cause instanceof TransactionException);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        TransactionErrorType errorType = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (errorType.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(errorType.name())
                .domain("transaction")
                .diagnosticMetadata(buildDiagnosticMetadata(cause, errorType))
                .build();
        details.add(info);

        return RootCauseResolution.of(errorType.httpStatus(), errorType.message(), details, errorType.retryability());
    }

    @NonNull
    private static TransactionErrorType classify(@NonNull Throwable cause) {
        return switch (cause) {
            case HeuristicCompletionException hce -> classifyHeuristic(hce);
            case TransactionTimedOutException ignored -> TransactionErrorType.TIMEOUT;
            case UnexpectedRollbackException ignored -> TransactionErrorType.UNEXPECTED_ROLLBACK;
            case CannotCreateTransactionException ignored -> TransactionErrorType.CANNOT_CREATE;
            case IllegalTransactionStateException ignored -> TransactionErrorType.ILLEGAL_STATE;
            case TransactionSystemException transactionSystemException -> classifySystemException(transactionSystemException);
            default -> TransactionErrorType.GENERIC;
        };
    }

    /**
     * Классифицирует {@code HeuristicCompletionException} по outcome state.
     *
     * <p>Outcome states (из Spring {@code HeuristicCompletionException}):
     * <ul>
     *   <li>{@code STATE_UNKNOWN (0)} — исход неизвестен</li>
     *   <li>{@code STATE_COMMITTED (1)} — heuristic commit</li>
     *   <li>{@code STATE_ROLLED_BACK (2)} — heuristic rollback</li>
     *   <li>{@code STATE_MIXED (3)} — mixed outcome (часть committed, часть rolled back)</li>
     * </ul>
     */
    @NonNull
    private static TransactionErrorType classifyHeuristic(@NonNull HeuristicCompletionException hce) {
        return switch (hce.getOutcomeState()) {
            case HeuristicCompletionException.STATE_COMMITTED -> TransactionErrorType.HEURISTIC_COMMITTED;
            case HeuristicCompletionException.STATE_ROLLED_BACK -> TransactionErrorType.HEURISTIC_ROLLED_BACK;
            case HeuristicCompletionException.STATE_MIXED -> TransactionErrorType.HEURISTIC_MIXED;
            default -> TransactionErrorType.HEURISTIC_UNKNOWN;
        };
    }

    @NonNull
    private static TransactionErrorType classifySystemException(@NonNull TransactionSystemException tse) {
        Throwable nested = tse.getOriginalException();
        if (nested == null) {
            nested = tse.getCause();
        }

        if (nested != null) {
            if (ClassUtils.isInstanceOf(JPA_ROLLBACK_EXCEPTION, nested)) {
                return TransactionErrorType.JPA_ROLLBACK;
            }

            if (isXaException(nested)) {
                return TransactionErrorType.XA_FAILURE;
            }
        }

        return TransactionErrorType.SYSTEM_ERROR;
    }

    private static boolean isXaException(@NonNull Throwable cause) {
        if (ClassUtils.isInstanceOf(XA_EXCEPTION, cause)) {
            return true;
        }

        return ClassUtils.isInstanceOf(JAKARTA_XA_EXCEPTION, cause);
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull TransactionErrorType errorType) {
        String message = cause.getMessage();

        DiagnosticMetadataBuilder builder = diagnosticMetadataFor(cause)
                .put("transactionLayer", errorType.layer())
                .putIfPresent("causeMessage",
                        StringUtils.hasText(message) ? StringUtils.truncate(message, MAX_CAUSE_MESSAGE_LENGTH) : null);

        builder.withRootCause(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH, MAX_CAUSE_MESSAGE_LENGTH);

        switch (errorType) {
            case XA_FAILURE -> enrichWithXaContext(cause, builder);
            case JPA_ROLLBACK -> enrichWithJpaRollbackDetails(cause, builder);
            case HEURISTIC_COMMITTED, HEURISTIC_ROLLED_BACK, HEURISTIC_MIXED, HEURISTIC_UNKNOWN -> {
                if (cause instanceof HeuristicCompletionException hce) {
                    builder.put("heuristicOutcome", resolveHeuristicOutcomeName(hce.getOutcomeState()));
                }
            }
            default -> { }
        }

        if (cause instanceof TransactionSystemException tse) {
            enrichWithNestedCauseType(tse, builder);
        }

        return builder.withStackHint().build();
    }

    private static void enrichWithXaContext(@NonNull Throwable cause,
                                            @NonNull DiagnosticMetadataBuilder builder) {
        Throwable candidate = cause;

        for (int depth = 0; depth < CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH && candidate != null; depth++) {
            if (ClassUtils.isInstanceOf(XA_EXCEPTION, candidate) || ClassUtils.isInstanceOf(JAKARTA_XA_EXCEPTION, candidate)) {
                ReflectionUtils.invokeMethod(candidate, "getMessage", rawMessage  -> {
                    if (rawMessage instanceof String xaMessage) {
                        builder.putTruncatedIfPresent("xaErrorMessage", xaMessage, MAX_CAUSE_MESSAGE_LENGTH);
                    }
                });

                try {
                    Field errorCodeField = candidate.getClass().getField("errorCode");
                    int errorCode = errorCodeField.getInt(candidate);
                    builder.put("xaErrorCode", String.valueOf(errorCode));

                    String codeName = resolveXaErrorCodeName(errorCode);
                    if (codeName != null) {
                        builder.put("xaErrorCodeName", codeName);
                    }
                } catch (Exception ignored) {
                }

                return;
            }

            candidate = candidate.getCause();
        }
    }

    /**
     * Извлекает причину JPA rollback при flush/commit.
     * Цепочка: {@code TransactionSystemException} → {@code RollbackException} → actual cause
     * (например, {@code ConstraintViolationException}, {@code PersistenceException}).
     */
    private static void enrichWithJpaRollbackDetails(@NonNull Throwable cause,
                                                     @NonNull DiagnosticMetadataBuilder builder) {
        if (!(cause instanceof TransactionSystemException tse)) {
            return;
        }

        Throwable original = tse.getOriginalException();
        if (original == null) {
            original = tse.getCause();
        }

        if (original != null && original.getCause() != null) {
            Throwable rollbackCause = original.getCause();
            builder.put("rollbackCauseType", rollbackCause.getClass().getName());

            String rollbackMsg = rollbackCause.getMessage();
            builder.putTruncatedIfPresent("rollbackCauseMessage", rollbackMsg, MAX_CAUSE_MESSAGE_LENGTH);
        }
    }

    /**
     * Маппинг числового XA error code в символическое имя из JTA/XA спецификации.
     */
    @Nullable
    private static String resolveXaErrorCodeName(int errorCode) {
        return switch (errorCode) {
            case 6   -> "XA_HEURRB";
            case 7   -> "XA_HEURCOM";
            case 8   -> "XA_HEURHAZ";
            case 9   -> "XA_HEURMIX";
            case 100 -> "XA_RBROLLBACK";
            case 101 -> "XA_RBCOMMFAIL";
            case 102 -> "XA_RBDEADLOCK";
            case 103 -> "XA_RBINTEGRITY";
            case 104 -> "XA_RBOTHER";
            case 105 -> "XA_RBPROTO";
            case 106 -> "XA_RBTIMEOUT";
            case 107 -> "XA_RBTRANSIENT";
            case -2  -> "XAER_ASYNC";
            case -3  -> "XAER_RMERR";
            case -4  -> "XAER_NOTA";
            case -5  -> "XAER_INVAL";
            case -6  -> "XAER_PROTO";
            case -7  -> "XAER_RMFAIL";
            case -8  -> "XAER_DUPID";
            case -9  -> "XAER_OUTSIDE";
            default  -> null;
        };
    }

    /**
     * FQCN обёрнутого исключения из {@code TransactionSystemException.getOriginalException()}.
     * Применяется для SYSTEM_ERROR, JPA_ROLLBACK и XA_FAILURE — все три
     * порождаются из {@code TransactionSystemException}.
     */
    private static void enrichWithNestedCauseType(@NonNull TransactionSystemException tse,
                                                  @NonNull DiagnosticMetadataBuilder builder) {
        Throwable original = tse.getOriginalException();
        if (original == null) {
            original = tse.getCause();
        }

        if (original != null) {
            builder.put("applicationExceptionType", original.getClass().getName());
        }
    }

    @NonNull
    private static String resolveHeuristicOutcomeName(int outcomeState) {
        return switch (outcomeState) {
            case HeuristicCompletionException.STATE_COMMITTED -> "COMMITTED";
            case HeuristicCompletionException.STATE_ROLLED_BACK -> "ROLLED_BACK";
            case HeuristicCompletionException.STATE_MIXED -> "MIXED";
            default -> "UNKNOWN";
        };
    }

    private enum TransactionErrorType {
        CANNOT_CREATE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "LOCAL",
                "Unable to create a new transaction. "
                        + "The transaction manager or underlying data source "
                        + "may be temporarily unavailable."
        ),

        TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "LOCAL",
                "The transaction timed out before completion. "
                        + "The configured transaction timeout was exceeded."
        ),

        /**
         * @implNote retryable=true безопасно только для идемпотентных операций.
         * Бизнес-логика до rollback могла частично выполниться (emails, external API).
         * Транзакции с non-idempotent side effects требуют explicit idempotency ключей
         * на уровне retry policy.
         */
        UNEXPECTED_ROLLBACK(
                HttpStatus.CONFLICT,
                Retryability.RETRYABLE,
                "LOCAL",
                "The transaction was unexpectedly rolled back. "
                        + "A nested transaction or the transaction manager "
                        + "marked the transaction as rollback-only."
        ),

        SYSTEM_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "LOCAL",
                "An unexpected transaction system error occurred. "
                        + "The transaction infrastructure encountered "
                        + "an unrecoverable error."
        ),

        ILLEGAL_STATE(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "LOCAL",
                "The transaction is in an illegal state for the requested operation. "
                        + "This indicates a transaction management configuration error."
        ),

        JPA_ROLLBACK(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "JPA",
                "The JPA transaction was rolled back during flush or commit. "
                        + "A persistence operation failed within the transaction."
        ),

        XA_FAILURE(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "XA",
                "A distributed (XA) transaction failure occurred. "
                        + "One or more transaction resources may be in an inconsistent state."
        ),

        /**
         * JTA/XA heuristic commit — resource manager принял решение commit
         * без координации с TX-менеджером.
         */
        HEURISTIC_COMMITTED(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "XA",
                "A heuristic commit occurred in a distributed transaction. "
                        + "The resource manager committed without coordination "
                        + "with the transaction manager."
        ),

        /**
         * JTA/XA heuristic rollback — resource manager принял решение rollback
         * без координации с TX-менеджером.
         */
        HEURISTIC_ROLLED_BACK(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "XA",
                "A heuristic rollback occurred in a distributed transaction. "
                        + "The resource manager rolled back without coordination "
                        + "with the transaction manager."
        ),

        /**
         * JTA/XA heuristic mixed — часть ресурсов committed, часть rolled back.
         * Наиболее критичный outcome: данные в несогласованном состоянии.
         */
        HEURISTIC_MIXED(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "XA",
                "A heuristic mixed outcome occurred in a distributed transaction. "
                        + "Some resources committed while others rolled back, "
                        + "resulting in a potentially inconsistent state."
        ),

        /**
         * JTA/XA heuristic unknown — исход не определён.
         */
        HEURISTIC_UNKNOWN(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "XA",
                "A distributed transaction completed with an unknown heuristic outcome. "
                        + "The final state of involved resources cannot be determined."
        ),

        GENERIC(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "LOCAL",
                "An unexpected transaction error occurred."
        );

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String layer;
        private final String message;

        TransactionErrorType(@NonNull HttpStatus httpStatus,
                             @NonNull Retryability retryability,
                             @NonNull String layer,
                             @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.layer = layer;
            this.message = message;
        }

        @NonNull
        HttpStatus httpStatus() { return httpStatus; }

        @NonNull
        Retryability retryability() { return retryability; }

        @NonNull
        String layer() { return layer; }

        @NonNull
        String message() { return message; }
    }
}
