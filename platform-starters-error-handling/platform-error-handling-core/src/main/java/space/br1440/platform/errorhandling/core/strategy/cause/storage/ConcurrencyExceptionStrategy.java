package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.jetbrains.annotations.NotNull;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.*;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.*;

/**
 * Стратегия обработки исключений конкурентного доступа к данным.
 *
 * <p>Специализированная стратегия для оптимистичных и пессимистичных блокировок,
 * обогащающая ответ диагностическими метаданными: тип сущности, идентификатор,
 * тип конфликта. Позволяет фронтенду отображать конкретное сообщение
 * («объект изменён другим пользователем, перезагрузите страницу») вместо
 * безликого 500.
 *
 * <h2>Типы конфликтов и retryability</h2>
 *
 * <p>Все типы конфликтов возвращают HTTP 409 Conflict и являются retryable:</p>
 * <ul>
 *   <li>{@code OPTIMISTIC_LOCK} — версия entity изменилась (fallback для «голого»
 *       {@code ConcurrencyFailureException}).</li>
 *   <li>{@code PESSIMISTIC_LOCK} — ресурс заблокирован конкурентной операцией.
 *       Retry уместен: блокировка временна.</li>
 *   <li>{@code DEADLOCK} — проигравшая сторона deadlock, транзакция откачена.
 *       {@code DeadlockLoserDataAccessException} extends
 *       {@code PessimisticLockingFailureException}.</li>
 *   <li>{@code LOCK_TIMEOUT} — не удалось захватить блокировку за отведённое время.</li>
 *   <li>{@code SERIALIZATION_FAILURE} — конфликт при SERIALIZABLE isolation level.</li>
 * </ul>
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <p>Метод {@code buildDiagnosticMetadata()} собирает поля в {@code diagnosticMetadata}
 * (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 *
 * <table>
 *   <caption>Поля diagnosticMetadata по типам конфликтов</caption>
 *   <tr><th>Поле</th><th>Типы</th><th>Описание</th></tr>
 *   <tr><td>{@code exceptionClass}</td><td>все</td>
 *       <td>FQCN исключения ({@code getName()}).</td></tr>
 *   <tr><td>{@code lockType}</td><td>все</td>
 *       <td>Тип конфликта: {@code OPTIMISTIC_LOCK}, {@code PESSIMISTIC_LOCK},
 *       {@code DEADLOCK}, {@code LOCK_TIMEOUT}, {@code SERIALIZATION_FAILURE}.</td></tr>
 *   <tr><td>{@code causeMessage}</td><td>все</td>
 *       <td>Сообщение исключения, truncated до 200 символов.</td></tr>
 *   <tr><td>{@code entityType}</td><td>{@code OPTIMISTIC_LOCK}</td>
 *       <td>Имя JPA/Hibernate entity (simpleName).</td></tr>
 *   <tr><td>{@code entityId}</td><td>{@code OPTIMISTIC_LOCK}</td>
 *       <td>Идентификатор сущности (если доступен через {@code @Id}).</td></tr>
 *   <tr><td>{@code lockMode}</td><td>{@code PESSIMISTIC_LOCK}</td>
 *       <td>JPA lock mode: {@code PESSIMISTIC_READ}, {@code PESSIMISTIC_WRITE},
 *       {@code PESSIMISTIC_FORCE_INCREMENT}. Извлекается из
 *       {@code jakarta.persistence.PessimisticLockException} через cause-chain.</td></tr>
 *   <tr><td>{@code sqlState}</td><td>{@code DEADLOCK}, {@code PESSIMISTIC_LOCK},
 *       {@code SERIALIZATION_FAILURE}, {@code LOCK_TIMEOUT}</td>
 *       <td>SQL State код ({@code 40P01} — PostgreSQL deadlock, {@code 40001} — serialization,
 *       и др.). Извлекается из {@code SQLException} через cause-chain (глубина до 5).</td></tr>
 *   <tr><td>{@code vendorErrorCode}</td><td>{@code DEADLOCK}, {@code PESSIMISTIC_LOCK},
 *       {@code SERIALIZATION_FAILURE}, {@code LOCK_TIMEOUT}</td>
 *       <td>Vendor-specific error code (напр. {@code 1213} — MySQL deadlock,
 *       {@code 1205} — MySQL lock wait timeout). {@code 0} не включается.</td></tr>
 *   <tr><td>{@code lockTimeoutHint}</td><td>{@code LOCK_TIMEOUT}</td>
 *       <td>Сообщение из {@code jakarta.persistence.LockTimeoutException}
 *       (protocol-level hint), truncated до 200 символов.</td></tr>
 *   <tr><td>{@code stackHint}</td><td>все</td>
 *       <td>Сжатый стек-трейс из прикладных фреймов.</td></tr>
 * </table>
 *
 * <h3>Безопасность (CWE-209)</h3>
 * <p>В {@code diagnosticMetadata} включаются только protocol-level данные:
 * <ul>
 *   <li>{@code sqlState} и {@code vendorErrorCode} — стандартизированные коды SQL,
 *       не содержат данных строк или пользовательской информации.</li>
 *   <li>{@code entityType}/{@code entityId} — добавляются только при явном предоставлении
 *       исключением: JPA {@code getEntity()} / Hibernate {@code getIdentifier()}.</li>
 *   <li>{@code lockTimeoutHint} — protocol-level сообщение JPA-провайдера,
 *       не содержит пользовательских данных.</li>
 * </ul>
 * Constraint names, SQL-запросы и данные строк намеренно <b>не включаются</b>.</p>
 */
public class ConcurrencyExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 200;
    private static final ClassLoader LOADER = ConcurrencyExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> HIBERNATE_STALE_OBJECT_CLASS =
            ReflectionUtils.loadOptionalClass("org.hibernate.StaleObjectStateException", LOADER);

    @Nullable
    private static final Class<?> JPA_OPTIMISTIC_LOCK_CLASS =
            ReflectionUtils.loadOptionalClass("jakarta.persistence.OptimisticLockException", LOADER);

    @Nullable
    private static final Class<?> JPA_PESSIMISTIC_LOCK_CLASS =
            ReflectionUtils.loadOptionalClass("jakarta.persistence.PessimisticLockException", LOADER);

    @Nullable
    private static final Class<?> JPA_LOCK_TIMEOUT_CLASS =
            ReflectionUtils.loadOptionalClass("jakarta.persistence.LockTimeoutException", LOADER);

    private static final int LOCK_CAUSE_CHAIN_DEPTH = 3;

    public ConcurrencyExceptionStrategy(@NonNull StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.CONCURRENCY, stackHintExtractor);
    }

    /**
     * 409 Conflict — конкурентный конфликт, не серверная ошибка.
     * Логируется на уровне WARN: предсказуемое событие при нормальной конкуренции.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.WARN;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return cause instanceof ConcurrencyFailureException
                || ClassUtils.isInstanceOf(JPA_OPTIMISTIC_LOCK_CLASS, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        ConcurrencyErrorType type = classify(cause);

        // Все ConcurrencyErrorType считаются retryable — конфликт блокировок разрешается повторной попыткой
        List<ErrorDetail> details = new ArrayList<>();
        details.add(new RetryInfoDetail());

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("persistence")
                .diagnosticMetadata(buildDiagnosticMetadata(cause, type))
                .build();
        details.add(info);

        HttpStatus httpStatus = getHttpStatus();
        String message = resolveMessage(type);

        return RootCauseResolution.retryable(httpStatus, message, details);
    }

    @NonNull
    @SuppressWarnings("deprecation")
    private static ConcurrencyErrorType classify(@NonNull Throwable throwable) {
        return switch (throwable) {
            case DeadlockLoserDataAccessException ignored -> ConcurrencyErrorType.DEADLOCK;
            case CannotAcquireLockException ignored -> ConcurrencyErrorType.LOCK_TIMEOUT;
            case CannotSerializeTransactionException ignored -> ConcurrencyErrorType.SERIALIZATION_FAILURE;
            case OptimisticLockingFailureException ignored -> ConcurrencyErrorType.OPTIMISTIC_LOCK;
            case PessimisticLockingFailureException ignored -> ConcurrencyErrorType.PESSIMISTIC_LOCK;
            default -> throw new AssertionError(
                    "ConcurrencyExceptionStrategy.classify() called for unsupported exception: "
                            + throwable.getClass().getName()
                            + ". If this fires, supportsCause() accepted a type that classify() cannot handle."
            );
        };
    }

    @NonNull
    private static HttpStatus getHttpStatus() {
        return HttpStatus.CONFLICT;
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable throwable,
                                                        @NonNull ConcurrencyErrorType type) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(throwable, stackHintExtractor);
        builder.put("lockType", type.conflictType());

        String message = throwable.getMessage();
        builder.putTruncatedIfPresent("causeMessage", message, MAX_CAUSE_MESSAGE_LENGTH);

        switch (type) {
            case OPTIMISTIC_LOCK -> {
                if (throwable instanceof OptimisticLockingFailureException e) {
                    enrichWithHibernateMetadata(e, builder);
                } else if (ClassUtils.isInstanceOf(JPA_OPTIMISTIC_LOCK_CLASS, throwable)) {
                    enrichWithJpaMetadata(throwable, builder);
                }
            }

            case PESSIMISTIC_LOCK -> {
                enrichWithPessimisticLockMetadata(throwable, builder);
                enrichWithSqlExceptionMetadata(throwable, builder);
            }

            case DEADLOCK, SERIALIZATION_FAILURE -> enrichWithSqlExceptionMetadata(throwable, builder);

            case LOCK_TIMEOUT -> {
                enrichWithSqlExceptionMetadata(throwable, builder);
                enrichWithLockTimeoutHint(throwable, builder);
            }
        }

        return builder.withStackHint().build();
    }

    @NonNull
    private static String resolveMessage(@NonNull ConcurrencyErrorType type) {
        return switch (type) {
            case OPTIMISTIC_LOCK -> "The resource was modified by another request. Reload the resource and retry.";
            case PESSIMISTIC_LOCK -> "The resource is locked by a concurrent operation. Retry after the operation completes.";
            case DEADLOCK -> "A database deadlock was detected. The transaction has been rolled back. Retry the request.";
            case LOCK_TIMEOUT -> "Cannot acquire a database lock due to a competing concurrent operation. Retry the request.";
            case SERIALIZATION_FAILURE -> "The transaction could not be serialized due to a concurrent modification. Retry the request.";
        };
    }

    private static void enrichWithPessimisticLockMetadata(@NonNull Throwable throwable,
                                                          @NonNull DiagnosticMetadataBuilder builder) {
        Throwable candidate = throwable;

        for (int depth = 0; depth < LOCK_CAUSE_CHAIN_DEPTH && candidate != null; depth++) {
            if (ClassUtils.isInstanceOf(JPA_PESSIMISTIC_LOCK_CLASS, candidate)) {
                ReflectionUtils.invokeMethod(candidate, "getLockMode", lockMode -> {
                    if (lockMode instanceof Enum<?> e) {
                        builder.put("lockMode", e.name());
                    }
                });

                return;
            }

            candidate = candidate.getCause();
        }
    }

    private static void enrichWithSqlExceptionMetadata(@NonNull Throwable throwable,
                                                       @NonNull DiagnosticMetadataBuilder builder) {
        SQLException sqlEx = CauseChainNavigator.findSqlException(throwable);
        if (sqlEx == null) {
            return;
        }

        String sqlState = sqlEx.getSQLState();
        if (StringUtils.hasText(sqlState)) {
            builder.put("sqlState", sqlState);
        }

        int errorCode = sqlEx.getErrorCode();
        if (errorCode != 0) {
            builder.put("vendorErrorCode", String.valueOf(errorCode));
        }
    }

    private static void enrichWithLockTimeoutHint(@NonNull Throwable throwable,
                                                  @NonNull DiagnosticMetadataBuilder builder) {
        Throwable candidate = throwable;

        for (int depth = 0; depth < LOCK_CAUSE_CHAIN_DEPTH && candidate != null; depth++) {
            if (ClassUtils.isInstanceOf(JPA_LOCK_TIMEOUT_CLASS, candidate)) {
                String timeoutMessage = candidate.getMessage();
                builder.putTruncatedIfPresent("lockTimeoutHint", timeoutMessage, MAX_CAUSE_MESSAGE_LENGTH);
                return;
            }

            candidate = candidate.getCause();
        }
    }

    private static void enrichWithHibernateMetadata(@NonNull Throwable exception,
                                                    @NonNull DiagnosticMetadataBuilder builder) {
        Throwable candidate = exception;

        for (int depth = 0; depth < LOCK_CAUSE_CHAIN_DEPTH && candidate != null; depth++) {
            if (ClassUtils.isInstanceOf(HIBERNATE_STALE_OBJECT_CLASS, candidate)) {
                extractHibernateFields(candidate, builder);
                return;
            }

            candidate = candidate.getCause();
        }
    }

    private static void extractHibernateFields(@NonNull Object staleException,
                                               @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(staleException, "getEntityName", value -> {
            if (value instanceof String name && StringUtils.hasText(name)) {
                int dot = name.lastIndexOf('.');
                builder.put("entityType", (dot >= 0) ? name.substring(dot + 1) : name);
            }
        });

        ReflectionUtils.invokeMethod(staleException, "getIdentifier", value -> {
            if (value != null) {
                builder.put("entityId", value.toString());
            }
        });
    }

    private static void enrichWithJpaMetadata(@NonNull Object jpaException,
                                              @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(jpaException, "getEntity", entity -> {
            if (entity == null) {
                return;
            }

            builder.put("entityType", entity.getClass().getName());
            extractJpaEntityId(entity, builder);
        });
    }

    private static void extractJpaEntityId(@NonNull Object entity,
                                           @NonNull DiagnosticMetadataBuilder builder) {
        try {
            Class<?> cls = entity.getClass();
            while (cls != null && cls != Object.class) {
                for (Field field : cls.getDeclaredFields()) {
                    boolean hasJpaId = Arrays.stream(field.getDeclaredAnnotations())
                            .anyMatch(a -> "jakarta.persistence.Id".equals(a.annotationType().getName()));
                    if (hasJpaId) {
                        field.setAccessible(true);
                        Object id = field.get(entity);
                        if (id != null) {
                            builder.put("entityId", id.toString());
                        }

                        return;
                    }
                }

                cls = cls.getSuperclass();
            }
        } catch (Exception ignored) {
            // Reflection недоступна или поле @Id не найдено — продолжаем без entityId
        }
    }

    private enum ConcurrencyErrorType {
        /**
         * Оптимистическая блокировка — версия изменилась.
         * Также используется как fallback для «голого» {@code ConcurrencyFailureException}.
         */
        OPTIMISTIC_LOCK("OPTIMISTIC_LOCK"),
        /**
         * Пессимистическая блокировка — ресурс заблокирован.
         */
        PESSIMISTIC_LOCK("PESSIMISTIC_LOCK"),
        /**
         * Deadlock — проигравшая сторона транзакции. Всегда retryable.
         * {@code DeadlockLoserDataAccessException} extends {@code PessimisticLockingFailureException}.
         */
        DEADLOCK("DEADLOCK"),
        /**
         * Не удалось получить блокировку (lock timeout).
         */
        LOCK_TIMEOUT("LOCK_TIMEOUT"),
        /**
         * Serialization failure при SERIALIZABLE isolation level.
         */
        SERIALIZATION_FAILURE("SERIALIZATION_FAILURE");

        private final String conflictType;

        ConcurrencyErrorType(@NonNull String conflictType) {
            this.conflictType = conflictType;
        }

        @NonNull
        public String conflictType() {
            return conflictType;
        }
    }
}
