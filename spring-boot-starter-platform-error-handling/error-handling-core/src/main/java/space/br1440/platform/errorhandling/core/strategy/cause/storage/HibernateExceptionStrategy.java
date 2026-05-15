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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Стратегия обработки нативных Hibernate-исключений ({@code org.hibernate.HibernateException})
 * как первопричины бизнес-исключений.
 *
 * <h2>Мотивация</h2>
 * <p>Когда {@code HibernateException} не проходит через Spring
 * {@code PersistenceExceptionTranslationPostProcessor} (код вне {@code @Repository},
 * самовызов, стартовые ошибки), исключения пролетают мимо всех существующих стратегий
 * и попадают в generic 500 без диагностики. Стратегия закрывает эту дыру для наиболее
 * частых Hibernate-специфичных сценариев.
 *
 * <h2>Обрабатываемые исключения</h2>
 * <pre>
 *   org.hibernate.HibernateException (base)
 *   │
 *   ├─ LazyInitializationException           → 500  LAZY_INITIALIZATION    not retryable
 *   │   Доступ к lazy-ассоциации (proxy/collection) вне persistence context.
 *   │   Ключевой тип для диагностики one-to-many проблем.
 *   │
 *   ├─ TransientObjectException              → 500  TRANSIENT_OBJECT       not retryable
 *   │   │  Transient entity передана операции, требующей persistent state.
 *   │   │
 *   │   └─ TransientPropertyValueException   → 500  TRANSIENT_PROPERTY     not retryable
 *   │       Entity holds reference to unsaved transient via specific property.
 *   │       Enrichment: propertyName, transientEntityName, propertyOwnerEntityName.
 *   │
 *   ├─ PersistentObjectException             → 500  DETACHED_ENTITY        not retryable
 *   │   Detached entity передана persist — требуется merge.
 *   │
 *   ├─ PropertyValueException                → 422  PROPERTY_VALUE         not retryable
 *   │   Hibernate-level not-null/not-empty валидация при flush.
 *   │
 *   ├─ ObjectNotFoundException               → 404  ENTITY_NOT_FOUND       not retryable
 *   │   Proxy не удалось инициализировать: entity с данным ID не существует.
 *   │
 *   ├─ ObjectDeletedException                → 409  DELETED_ENTITY         not retryable
 *   │   Операция ссылается на удалённую entity.
 *   │
 *   ├─ StaleObjectStateException             → 409  STALE_STATE            retryable
 *   │   extends StaleStateException
 *   │   Optimistic lock: версия entity изменилась.
 *   │
 *   ├─ StaleStateException                   → 409  STALE_STATE            retryable
 *   │   UPDATE/DELETE с rowsAffected=0 или version conflict.
 *   │
 *   ├─ NonUniqueObjectException              → 409  DUPLICATE_OBJECT_IN_SESSION  not retryable
 *   │   Два разных Java-объекта с одинаковым ID в одной Session.
 *   │
 *   ├─ MultipleBagFetchException             → 500  MULTIPLE_BAG_FETCH     not retryable
 *   │   (org.hibernate.loader) JOIN FETCH нескольких bag-коллекций.
 *   │
 *   ├─ IdentifierGenerationException         → 500  ID_GENERATION_FAILED   not retryable
 *   │   (org.hibernate.id) Сбой генерации ID.
 *   │
 *   ├─ TypeMismatchException                 → 500  TYPE_MISMATCH          not retryable
 *   │   Тип ID не соответствует объявленному.
 *   │
 *   ├─ InstantiationException                → 500  INSTANTIATION_FAILED   not retryable
 *   │   Не удалось создать экземпляр entity.
 *   │
 *   ├─ NonUniqueResultException              → 500  NON_UNIQUE_RESULT      not retryable
 *   │   Запрос вернул &gt; 1 строки при ожидании uniqueResult.
 *   │
 *   ├─ QueryException                        → 500  QUERY_ERROR            not retryable
 *   │   Ошибка HQL/JPQL-запроса.
 *   │
 *   ├─ SessionException                      → 500  SESSION_CLOSED         not retryable
 *   │   Сессия закрыта или в невалидном состоянии.
 *   │
 *   ├─ WrongClassException                   → 500  WRONG_CLASS            not retryable
 *   │   Entity загружена с неожиданным типом (inheritance/discriminator).
 *   │
 *   ├─ MappingException                      → 500  MAPPING_ERROR          not retryable
 *   │   Ошибка ORM-маппинга (аннотации, конфигурация).
 *   │
 *   ├─ (message-based detection)
 *   │   ├─ "shared references to a collection" → 500  SHARED_COLLECTION_REFERENCE  not retryable
 *   │   └─ "identifier ... was altered"        → 500  IDENTIFIER_ALTERED           not retryable
 *   │
 *   └─ JDBCException                         → (исключено из supportsCause)
 *       Обёртка над SQLException; транслируется Spring в DataAccessException.
 *       Обрабатывается {@link DataAccessExceptionStrategy}.
 * </pre>
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
 * <h3>Type-specific поля</h3>
 * <table>
 *   <caption>Поля diagnosticMetadata по типам</caption>
 *   <tr><th>Поле</th><th>Типы</th><th>Описание</th></tr>
 *   <tr><td>{@code associationType}</td><td>{@code LAZY_INITIALIZATION}</td>
 *       <td>{@code "proxy"} или {@code "collection"} — тип lazy-ассоциации.</td></tr>
 *   <tr><td>{@code entityName}</td><td>{@code LAZY_INITIALIZATION}, {@code TRANSIENT_PROPERTY},
 *       {@code TRANSIENT_OBJECT}, {@code DETACHED_ENTITY}, {@code PROPERTY_VALUE},
 *       {@code ENTITY_NOT_FOUND}, {@code DELETED_ENTITY}, {@code STALE_STATE},
 *       {@code DUPLICATE_OBJECT_IN_SESSION}, {@code WRONG_CLASS},
 *       {@code SHARED_COLLECTION_REFERENCE}, {@code IDENTIFIER_ALTERED}</td>
 *       <td>SimpleName класса entity (без package).</td></tr>
 *   <tr><td>{@code associationPath}</td><td>{@code LAZY_INITIALIZATION}</td>
 *       <td>Имя коллекции/ассоциации (из {@code collection of role:}).</td></tr>
 *   <tr><td>{@code proxyIdentifier}</td><td>{@code LAZY_INITIALIZATION}</td>
 *       <td>ID proxy-объекта (sanitized через {@code SAFE_ID_PATTERN}).</td></tr>
 *   <tr><td>{@code propertyName}</td><td>{@code PROPERTY_VALUE}, {@code TRANSIENT_PROPERTY}</td>
 *       <td>Имя свойства entity.</td></tr>
 *   <tr><td>{@code transientEntityName}</td><td>{@code TRANSIENT_PROPERTY}</td>
 *       <td>SimpleName класса transient entity, на которую ссылается свойство.</td></tr>
 *   <tr><td>{@code entityId}</td><td>{@code ENTITY_NOT_FOUND}, {@code DELETED_ENTITY},
 *       {@code STALE_STATE}, {@code DUPLICATE_OBJECT_IN_SESSION}, {@code WRONG_CLASS}</td>
 *       <td>Идентификатор entity (sanitized: composite keys — только simpleName типа).</td></tr>
 *   <tr><td>{@code bagRoles}</td><td>{@code MULTIPLE_BAG_FETCH}</td>
 *       <td>Имена bag-коллекций (best-effort, internal Hibernate API).</td></tr>
 *   <tr><td>{@code collectionName}</td><td>{@code SHARED_COLLECTION_REFERENCE}</td>
 *       <td>Имя коллекции с shared reference.</td></tr>
 *   <tr><td>{@code oldIdentifier}</td><td>{@code IDENTIFIER_ALTERED}</td>
 *       <td>Предыдущее значение ID (sanitized).</td></tr>
 *   <tr><td>{@code newIdentifier}</td><td>{@code IDENTIFIER_ALTERED}</td>
 *       <td>Новое значение ID (sanitized).</td></tr>
 *   <tr><td>{@code queryString}</td><td>{@code QUERY_ERROR}</td>
 *       <td>HQL/JPQL-запрос (truncated до 200 символов).
 *       <b>Внимание:</b> может содержать PII из встроенных литералов.</td></tr>
 * </table>
 *
 * <h3>Безопасность (CWE-209)</h3>
 * <ul>
 *   <li>{@code entityName} — simpleName без package (не раскрывает структуру пакетов).</li>
 *   <li>{@code propertyName} — имя поля entity (schema-level metadata).</li>
 *   <li>{@code entityId} — sanitized: простые типы ({@code Long}, {@code UUID}) — значение;
 *       composite keys ({@code @EmbeddedId}) — только {@code getClass().getName()} (FQCN)
 *       без значения.</li>
 *   <li>{@code queryString} — truncated до 200 символов, только в {@code diagnosticMetadata}.
 *       <b>Внимание:</b> HQL/JPQL может содержать литеральные значения
 *       ({@code WHERE e.email = 'user@example.com'}). При экспорте {@code diagnosticMetadata}
 *       в централизованный лог-агрегатор (ELK, Loki, Splunk) без маскирования —
 *       потенциальная утечка PII.</li>
 *   <li>{@code causeMessage} — truncated, только в {@code diagnosticMetadata}.</li>
 *   <li>Значения данных строк, SQL-запросы вне HQL, JDBC URL — не включаются.</li>
 * </ul>
 */
public class HibernateExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_ID_LENGTH = 100;

    private static final ClassLoader LOADER = HibernateExceptionStrategy.class.getClassLoader();

    /**
     * Паттерн для валидации entityId из {@code getIdentifier().toString()}.
     * Пропускает простые типы (Long, UUID, String PK с безопасными символами).
     * Composite keys ({@code @EmbeddedId}) с произвольным {@code toString()} не пройдут —
     * для них сохраняется только {@code getClass().getName()} (FQCN).
     */
    private static final Pattern SAFE_ID_PATTERN =
            Pattern.compile("^[\\w\\-.:]+$");

    private static final Pattern PROXY_PATTERN =
            Pattern.compile("\\[([^#]+)#([^]]+)]");

    private static final Pattern COLLECTION_ROLE_PATTERN =
            Pattern.compile("collection of role:\\s*(\\w[\\w.$]*)\\.(\\w+)");

    private static final Pattern ENTITY_REF_PATTERN =
            Pattern.compile(":\\s*([\\w.$]+)\\s*$");

    private static final Pattern SHARED_COLLECTION_PATTERN =
            Pattern.compile("shared references to a collection:\\s*([\\w.$]+)\\.(\\w+)");

    private static final Pattern IDENTIFIER_ALTERED_PATTERN =
            Pattern.compile("identifier of an instance of ([\\w.$]+) was altered from (\\S+) to (\\S+)");

    @Nullable
    private static final Class<?> HIBERNATE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.HibernateException", LOADER);

    @Nullable
    private static final Class<?> JDBC_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.JDBCException", LOADER);

    @Nullable
    private static final Class<?> LAZY_INIT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.LazyInitializationException", LOADER);

    @Nullable
    private static final Class<?> TRANSIENT_OBJECT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.TransientObjectException", LOADER);

    @Nullable
    private static final Class<?> PERSISTENT_OBJECT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.PersistentObjectException", LOADER);

    @Nullable
    private static final Class<?> PROPERTY_VALUE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.PropertyValueException", LOADER);

    @Nullable
    private static final Class<?> OBJECT_NOT_FOUND_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.ObjectNotFoundException", LOADER);

    @Nullable
    private static final Class<?> OBJECT_DELETED_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.ObjectDeletedException", LOADER);

    @Nullable
    private static final Class<?> STALE_OBJECT_STATE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.StaleObjectStateException", LOADER);

    @Nullable
    private static final Class<?> STALE_STATE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.StaleStateException", LOADER);

    @Nullable
    private static final Class<?> QUERY_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.QueryException", LOADER);

    @Nullable
    private static final Class<?> NON_UNIQUE_RESULT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.NonUniqueResultException", LOADER);

    @Nullable
    private static final Class<?> SESSION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.SessionException", LOADER);

    @Nullable
    private static final Class<?> WRONG_CLASS_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.WrongClassException", LOADER);

    @Nullable
    private static final Class<?> MAPPING_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.MappingException", LOADER);

    @Nullable
    private static final Class<?> TRANSIENT_PROPERTY_VALUE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.TransientPropertyValueException", LOADER);

    @Nullable
    private static final Class<?> NON_UNIQUE_OBJECT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.NonUniqueObjectException", LOADER);

    @Nullable
    private static final Class<?> MULTIPLE_BAG_FETCH_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.loader.MultipleBagFetchException", LOADER);

    @Nullable
    private static final Class<?> ID_GENERATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.id.IdentifierGenerationException", LOADER);

    @Nullable
    private static final Class<?> TYPE_MISMATCH_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.TypeMismatchException", LOADER);

    @Nullable
    private static final Class<?> INSTANTIATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.hibernate.InstantiationException", LOADER);

    public HibernateExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.HIBERNATE, stackHintExtractor);
    }

    /**
     * Матчит {@code org.hibernate.HibernateException} и все подтипы,
     * <b>кроме</b> {@code JDBCException} — обёртки над {@code SQLException},
     * которую {@link DataAccessExceptionStrategy} (150) классифицирует лучше
     * (SQL State, vendor error code, constraint name).
     */
    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        if (!ClassUtils.isInstanceOf(HIBERNATE_EXCEPTION, cause)) {
            return false;
        }

        return !ClassUtils.isInstanceOf(JDBC_EXCEPTION, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        HibernateErrorType type = classify(cause);

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

    /**
     * Классифицирует {@code HibernateException} в один из {@link HibernateErrorType}.
     *
     * <p><b>Порядок проверок критичен</b> из-за наследования:
     * <ul>
     *   <li>{@code TransientPropertyValueException} extends {@code TransientObjectException}
     *       — должен стоять до {@code TransientObjectException}, иначе потеряет
     *       специфичный enrichment ({@code propertyName}, {@code transientEntityName}).
     *       <b>Не связан</b> наследованием с {@code PropertyValueException}
     *       (разные ветки: {@code TransientObjectException} vs {@code HibernateException}).</li>
     *   <li>{@code StaleObjectStateException} extends {@code StaleStateException}
     *       — должен стоять до {@code StaleStateException}.</li>
     *   <li>{@code ObjectNotFoundException} extends {@code UnresolvableObjectException}
     *       — проверяется напрямую.</li>
     *   <li>{@code NonUniqueResultException} проверяется до {@code QueryException},
     *       т.к. в ранних версиях Hibernate мог быть подтипом.</li>
     * </ul>
     *
     * <p>Message-based типы ({@code SHARED_COLLECTION_REFERENCE}, {@code IDENTIFIER_ALTERED})
     * проверяются <b>последними</b>, перед {@code GENERIC} fallback — только если ни один
     * type-based check не сработал.
     */
    @NonNull
    private static HibernateErrorType classify(@NonNull Throwable cause) {
        // Группа 1: Entity Lifecycle (primary use case)
        if (ClassUtils.isInstanceOf(LAZY_INIT_EXCEPTION, cause)) {
            return HibernateErrorType.LAZY_INITIALIZATION;
        }
        if (ClassUtils.isInstanceOf(PROPERTY_VALUE_EXCEPTION, cause)) {
            return HibernateErrorType.PROPERTY_VALUE;
        }

        // TransientPropertyValueException extends TransientObjectException — must be before it.
        // NOT related to PropertyValueException (different inheritance branches).
        if (ClassUtils.isInstanceOf(TRANSIENT_PROPERTY_VALUE_EXCEPTION, cause)) {
            return HibernateErrorType.TRANSIENT_PROPERTY;
        }
        if (ClassUtils.isInstanceOf(TRANSIENT_OBJECT_EXCEPTION, cause)) {
            return HibernateErrorType.TRANSIENT_OBJECT;
        }
        if (ClassUtils.isInstanceOf(PERSISTENT_OBJECT_EXCEPTION, cause)) {
            return HibernateErrorType.DETACHED_ENTITY;
        }
        if (ClassUtils.isInstanceOf(OBJECT_DELETED_EXCEPTION, cause)) {
            return HibernateErrorType.DELETED_ENTITY;
        }

        // StaleObjectStateException before StaleStateException (inheritance)
        if (ClassUtils.isInstanceOf(STALE_OBJECT_STATE_EXCEPTION, cause)) {
            return HibernateErrorType.STALE_STATE;
        }
        if (ClassUtils.isInstanceOf(STALE_STATE_EXCEPTION, cause)) {
            return HibernateErrorType.STALE_STATE;
        }
        if (ClassUtils.isInstanceOf(OBJECT_NOT_FOUND_EXCEPTION, cause)) {
            return HibernateErrorType.ENTITY_NOT_FOUND;
        }

        // Группа 2: Session identity / fetch
        if (ClassUtils.isInstanceOf(NON_UNIQUE_OBJECT_EXCEPTION, cause)) {
            return HibernateErrorType.DUPLICATE_OBJECT_IN_SESSION;
        }
        if (ClassUtils.isInstanceOf(MULTIPLE_BAG_FETCH_EXCEPTION, cause)) {
            return HibernateErrorType.MULTIPLE_BAG_FETCH;
        }

        // Группа 3: ID / Type
        if (ClassUtils.isInstanceOf(ID_GENERATION_EXCEPTION, cause)) {
            return HibernateErrorType.ID_GENERATION_FAILED;
        }
        if (ClassUtils.isInstanceOf(TYPE_MISMATCH_EXCEPTION, cause)) {
            return HibernateErrorType.TYPE_MISMATCH;
        }
        if (ClassUtils.isInstanceOf(INSTANTIATION_EXCEPTION, cause)) {
            return HibernateErrorType.INSTANTIATION_FAILED;
        }

        // Группа 4: Query
        if (ClassUtils.isInstanceOf(NON_UNIQUE_RESULT_EXCEPTION, cause)) {
            return HibernateErrorType.NON_UNIQUE_RESULT;
        }
        if (ClassUtils.isInstanceOf(QUERY_EXCEPTION, cause)) {
            return HibernateErrorType.QUERY_ERROR;
        }

        // Группа 5: Session / Mapping
        if (ClassUtils.isInstanceOf(SESSION_EXCEPTION, cause)) {
            return HibernateErrorType.SESSION_CLOSED;
        }
        if (ClassUtils.isInstanceOf(WRONG_CLASS_EXCEPTION, cause)) {
            return HibernateErrorType.WRONG_CLASS;
        }
        if (ClassUtils.isInstanceOf(MAPPING_EXCEPTION, cause)) {
            return HibernateErrorType.MAPPING_ERROR;
        }

        // Группа 6: Message-based detection (no dedicated exception subclass)
        return classifyByMessage(cause);
    }

    @NonNull
    private static HibernateErrorType classifyByMessage(@NonNull Throwable cause) {
        String msg = cause.getMessage();
        if (msg == null) {
            return HibernateErrorType.GENERIC;
        }

        if (msg.contains("shared references to a collection")) {
            return HibernateErrorType.SHARED_COLLECTION_REFERENCE;
        }

        if (msg.contains("identifier of an instance of") && msg.contains("was altered")) {
            return HibernateErrorType.IDENTIFIER_ALTERED;
        }

        return HibernateErrorType.GENERIC;
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull HibernateErrorType type) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        String message = cause.getMessage();
        builder.putTruncatedIfPresent("causeMessage", message, MAX_CAUSE_MESSAGE_LENGTH);

        switch (type) {
            case LAZY_INITIALIZATION -> enrichWithLazyInitMetadata(message, builder);
            case TRANSIENT_PROPERTY -> enrichWithTransientPropertyMetadata(cause, builder);
            case TRANSIENT_OBJECT, DETACHED_ENTITY -> enrichWithEntityRefFromMessage(message, builder);
            case PROPERTY_VALUE -> enrichWithPropertyValueMetadata(cause, builder);
            case ENTITY_NOT_FOUND, DELETED_ENTITY, STALE_STATE, DUPLICATE_OBJECT_IN_SESSION, WRONG_CLASS -> enrichWithIdentifiableMetadata(cause, builder);
            case MULTIPLE_BAG_FETCH -> enrichWithMultipleBagFetchMetadata(cause, builder);
            case QUERY_ERROR -> enrichWithQueryMetadata(cause, builder);
            case SHARED_COLLECTION_REFERENCE -> enrichWithSharedCollectionMetadata(message, builder);
            case IDENTIFIER_ALTERED -> enrichWithIdentifierAlteredMetadata(message, builder);
            default -> { }
        }

        return builder.withStackHint().build();
    }

    /**
     * Извлекает из сообщения {@code LazyInitializationException} тип ассоциации,
     * имя entity и path/identifier для диагностики one-to-many проблем.
     *
     * <p>Два формата сообщений Hibernate:
     * <ul>
     *   <li>Proxy: {@code "could not initialize proxy [com.example.Order#42] - no Session"}</li>
     *   <li>Collection: {@code "failed to lazily initialize a collection of role: com.example.Order.items"}</li>
     * </ul>
     */
    private static void enrichWithLazyInitMetadata(@Nullable String message,
                                                   @NonNull DiagnosticMetadataBuilder builder) {
        if (message == null) {
            return;
        }

        Matcher proxyMatcher = PROXY_PATTERN.matcher(message);
        if (proxyMatcher.find()) {
            builder.put("associationType", "proxy");

            String fqcn = proxyMatcher.group(1);
            builder.put("entityName", extractSimpleName(fqcn));

            String rawId = proxyMatcher.group(2);
            String sanitized = sanitizeRawIdentifier(rawId);
            if (sanitized != null) {
                builder.put("proxyIdentifier", sanitized);
            }
            return;
        }

        Matcher collectionMatcher = COLLECTION_ROLE_PATTERN.matcher(message);
        if (collectionMatcher.find()) {
            builder.put("associationType", "collection");

            String ownerFqcn = collectionMatcher.group(1);
            builder.put("entityName", extractSimpleName(ownerFqcn));

            builder.put("associationPath", collectionMatcher.group(2));
        }
    }

    private static void enrichWithEntityRefFromMessage(@Nullable String message,
                                                       @NonNull DiagnosticMetadataBuilder builder) {
        if (message == null) {
            return;
        }

        Matcher matcher = ENTITY_REF_PATTERN.matcher(message);
        if (matcher.find()) {
            builder.put("entityName", extractSimpleName(matcher.group(1)));
        }
    }

    private static void enrichWithPropertyValueMetadata(@NonNull Throwable cause,
                                                        @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getEntityName", value -> {
            if (value instanceof String name && StringUtils.hasText(name)) {
                builder.put("entityName", extractSimpleName(name));
            }
        });

        ReflectionUtils.invokeMethod(cause, "getPropertyName", value -> {
            if (value instanceof String name && StringUtils.hasText(name)) {
                builder.put("propertyName", name);
            }
        });
    }

    private static void enrichWithIdentifiableMetadata(@NonNull Throwable cause,
                                                       @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getEntityName", value -> {
            if (value instanceof String name && StringUtils.hasText(name)) {
                builder.put("entityName", extractSimpleName(name));
            }
        });

        ReflectionUtils.invokeMethod(cause, "getIdentifier", value -> {
            String sanitized = sanitizeIdentifier(value);
            if (sanitized != null) {
                builder.put("entityId", sanitized);
            }
        });
    }

    private static void enrichWithQueryMetadata(@NonNull Throwable cause,
                                                @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getQueryString", value -> {
            if (value instanceof String queryString) {
                builder.putTruncatedIfPresent("queryString", queryString, MAX_QUERY_LENGTH);
            }
        });
    }

    private static void enrichWithTransientPropertyMetadata(@NonNull Throwable cause,
                                                            @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getPropertyOwnerEntityName", value -> {
            if (value instanceof String name && StringUtils.hasText(name)) {
                builder.put("entityName", extractSimpleName(name));
            }
        });

        ReflectionUtils.invokeMethod(cause, "getPropertyName", value -> {
            if (value instanceof String name && StringUtils.hasText(name)) {
                builder.put("propertyName", name);
            }
        });

        ReflectionUtils.invokeMethod(cause, "getTransientEntityName", value -> {
            if (value instanceof String name && StringUtils.hasText(name)) {
                builder.put("transientEntityName", extractSimpleName(name));
            }
        });
    }

    private static void enrichWithMultipleBagFetchMetadata(@NonNull Throwable cause,
                                                           @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getBagRoles", value -> {
            if (value instanceof List<?> roles && !roles.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Object role : roles) {
                    if (role instanceof String s && StringUtils.hasText(s)) {
                        if (!sb.isEmpty()) {
                            sb.append(", ");
                        }

                        sb.append(extractSimpleName(s));
                    }
                }

                if (!sb.isEmpty()) {
                    builder.put("bagRoles", sb.toString());
                }
            }
        });
    }

    private static void enrichWithSharedCollectionMetadata(@Nullable String message,
                                                           @NonNull DiagnosticMetadataBuilder builder) {
        if (message == null) {
            return;
        }

        Matcher matcher = SHARED_COLLECTION_PATTERN.matcher(message);
        if (matcher.find()) {
            builder.put("entityName", extractSimpleName(matcher.group(1)));
            builder.put("collectionName", matcher.group(2));
        }
    }

    private static void enrichWithIdentifierAlteredMetadata(@Nullable String message,
                                                            @NonNull DiagnosticMetadataBuilder builder) {
        if (message == null) {
            return;
        }

        Matcher matcher = IDENTIFIER_ALTERED_PATTERN.matcher(message);
        if (matcher.find()) {
            builder.put("entityName", extractSimpleName(matcher.group(1)));

            String oldId = sanitizeRawIdentifier(matcher.group(2));
            if (oldId != null) {
                builder.put("oldIdentifier", oldId);
            }

            String newId = sanitizeRawIdentifier(matcher.group(3));
            if (newId != null) {
                builder.put("newIdentifier", newId);
            }
        }
    }

    @Nullable
    private static String sanitizeIdentifier(@Nullable Object identifier) {
        if (identifier == null) {
            return null;
        }

        String raw = identifier.toString();
        if ((raw.length() > MAX_ID_LENGTH) || !SAFE_ID_PATTERN.matcher(raw).matches()) {
            return identifier.getClass().getName();
        }

        return raw;
    }

    @Nullable
    private static String sanitizeRawIdentifier(@Nullable String rawId) {
        if (!StringUtils.hasText(rawId)) {
            return null;
        }

        if ((rawId.length() > MAX_ID_LENGTH) || !SAFE_ID_PATTERN.matcher(rawId).matches()) {
            return "[complex]";
        }

        return rawId;
    }

    @NonNull
    private static String extractSimpleName(@NonNull String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        return (lastDot >= 0) ? fqcn.substring(lastDot + 1) : fqcn;
    }

    private enum HibernateErrorType implements ErrorClassification {

        LAZY_INITIALIZATION(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Entity association accessed outside of an active persistence context. "
                        + "Review the fetch strategy or transaction boundary."
        ),

        TRANSIENT_PROPERTY(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An entity holds a reference to an unsaved transient entity via a property. "
                        + "Save the referenced entity first or configure cascade."
        ),

        TRANSIENT_OBJECT(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "A transient (unsaved) entity was referenced by a persistent entity. "
                        + "Save the transient instance before flushing or configure cascade."
        ),

        DETACHED_ENTITY(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "A detached entity was passed to an operation that requires a managed entity. "
                        + "Merge the entity before persisting."
        ),

        PROPERTY_VALUE(
                HttpStatus.UNPROCESSABLE_ENTITY,
                Retryability.NOT_RETRYABLE,
                "An entity property violates a persistence-level constraint. "
                        + "A required property is null or references a transient instance."
        ),

        ENTITY_NOT_FOUND(
                HttpStatus.NOT_FOUND,
                Retryability.NOT_RETRYABLE,
                "The referenced entity does not exist. "
                        + "The entity may have been deleted or the identifier is invalid."
        ),

        DELETED_ENTITY(
                HttpStatus.CONFLICT,
                Retryability.NOT_RETRYABLE,
                "The operation references an entity that has been deleted."
        ),

        STALE_STATE(
                HttpStatus.CONFLICT,
                Retryability.RETRYABLE,
                "The entity was modified by another transaction. "
                        + "Reload and retry the operation."
        ),

        DUPLICATE_OBJECT_IN_SESSION(
                HttpStatus.CONFLICT,
                Retryability.NOT_RETRYABLE,
                "A different object with the same identifier value was already "
                        + "associated with the session."
        ),

        MULTIPLE_BAG_FETCH(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Cannot simultaneously fetch multiple bag collections. "
                        + "Use Set instead of List, or split into separate queries."
        ),

        ID_GENERATION_FAILED(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Failed to generate an entity identifier. "
                        + "Verify the ID generation strategy and field type compatibility."
        ),

        TYPE_MISMATCH(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The provided identifier type does not match the declared entity ID type."
        ),

        INSTANTIATION_FAILED(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Cannot instantiate entity class. "
                        + "Ensure a public or protected no-argument constructor exists."
        ),

        NON_UNIQUE_RESULT(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "A query expected a unique result but returned multiple rows."
        ),

        QUERY_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The persistence query is invalid. "
                        + "This indicates a query construction error."
        ),

        SESSION_CLOSED(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The persistence session is closed or in an invalid state "
                        + "for the requested operation."
        ),

        WRONG_CLASS(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An entity was loaded with an unexpected type. "
                        + "This indicates an inheritance mapping or discriminator issue."
        ),

        MAPPING_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An ORM mapping configuration error was detected. "
                        + "Review entity annotations and persistence configuration."
        ),

        SHARED_COLLECTION_REFERENCE(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Two entity instances share the same collection reference. "
                        + "Each entity must own a distinct collection instance."
        ),

        IDENTIFIER_ALTERED(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The identifier of a managed entity was modified. "
                        + "Entity identifiers are immutable after persistence."
        ),

        GENERIC(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An unexpected persistence provider error occurred."
        );

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        HibernateErrorType(@NonNull HttpStatus httpStatus,
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
