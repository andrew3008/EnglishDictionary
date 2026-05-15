package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.CauseMetadataEnricher;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Стратегия обработки исключений ClickHouse Java client v2 ({@code 0.7+}) и JDBC v2
 * как первопричины бизнес-исключений.
 *
 * <p>Все ClickHouse-классы загружаются через reflection — стратегия компилируется
 * и загружается без наличия {@code com.clickhouse:clickhouse-client} на classpath.
 * Условие активации обеспечивается
 * {@code @ConditionalOnClass(name = "com.clickhouse.client.api.ClickHouseException")}
 * в auto-configuration.
 *
 * <h2>Поддерживаемые иерархии исключений</h2>
 *
 * <h3>Native client v2 ({@code com.clickhouse.client.api})</h3>
 * <pre>
 *   ClickHouseException (base, extends RuntimeException)
 *   │   getQueryId(), isRetryable()
 *   │
 *   ├── ServerException                          getCode(), getTransportProtocolCode(),
 *   │                                            getQueryId(), isRetryable()
 *   │
 *   ├── ClientException                          клиентская ошибка (HTTP read, parsing)
 *   │   └── ClientMisconfigurationException      неверная конфигурация клиента
 *   │
 *   ├── ConnectionInitiationException            failure handshake / TLS / DNS
 *   │                                            (по умолчанию retryable=true в драйвере)
 *   └── DataTransferException                    network read/write protocol error
 * </pre>
 *
 * <h3>JDBC driver v2 ({@code com.clickhouse.jdbc})</h3>
 * <pre>
 *   java.sql.SQLException(message, sqlState, vendorCode=ServerException.getCode(), cause=ServerException)
 *     ├── sqlState ∈ {"22000", "08000", "HY000"}    нестандартные для Spring SQLErrorCodeTranslator
 *     └── cause: ServerException                    обнаруживается CauseChainResolver
 * </pre>
 *
 * <h2>Классификация серверных ошибок ClickHouse</h2>
 * <p>Двухуровневый dispatch: точное совпадение по серверному коду
 * ({@link ClickHouseErrorType}) → fallback по транспортному HTTP-статусу
 * ({@code ServerException.getTransportProtocolCode()}).
 *
 * <p>Список retryable-кодов выровнен с официальной реализацией
 * {@code ServerException.discoverIsRetryable()} из ветки {@code main} clickhouse-java
 * (PR #2453, June 2025): {@code 3, 107, 159, 164, 202, 203, 209, 210, 241, 242, 252,
 * 285, 319, 425, 999} и расширен логически близкими по семантике кодами
 * ({@code 198 DNS_ERROR}, {@code 254 NO_ACTIVE_REPLICAS}, {@code 286 UNSATISFIED_QUORUM},
 * {@code 364 RECEIVED_ERROR_TOO_MANY_REQUESTS} и т.д.).
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <p>Поля {@code diagnosticMetadata} (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения.</li>
 *   <li>{@code causeMessage} — сообщение исключения (truncated до 300 символов).</li>
 *   <li>{@code chErrorCode} — серверный код ошибки ClickHouse (например {@code 159}).</li>
 *   <li>{@code chErrorCodeName} — символьное имя кода из {@code ErrorCodes.cpp}
 *       (например {@code TIMEOUT_EXCEEDED}). Включается только при наличии
 *       соответствующей записи во встроенном словаре имён.</li>
 *   <li>{@code chTransportStatus} — HTTP-статус транспорта из
 *       {@code ServerException.getTransportProtocolCode()}.</li>
 *   <li>{@code chQueryId} — query ID из {@code ServerException.getQueryId()}.
 *       Критичен для SRE для поиска записи в {@code system.query_log}.</li>
 *   <li>{@code chServerRetryableHint} — {@code "true"}/{@code "false"} как hint
 *       драйвера через {@code ServerException.isRetryable()}; дополняет
 *       классификацию стратегии и помогает обнаруживать расхождения
 *       при обновлениях драйвера.</li>
 *   <li>{@code stackHint} — сжатый стек-трейс из прикладных фреймов.</li>
 * </ul>
 *
 * <h3>Безопасность (CWE-209 / CWE-312)</h3>
 * <p>{@code chErrorCode}, {@code chErrorCodeName}, {@code chTransportStatus}
 * и {@code chQueryId} — protocol-level metadata, не содержащие пользовательских
 * данных или credentials. Сообщение исключения ({@code causeMessage}) включается
 * только в {@code diagnosticMetadata} (SRE-only) и truncated до 300 символов.
 * SQL-запросы и значения колонок ClickHouse часто появляются в сообщениях ошибок —
 * стратегия не транслирует {@code causeMessage} в HTTP-ответ клиенту.
 */
public class ClickHouseExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;

    private static final ClassLoader LOADER = ClickHouseExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> CLICKHOUSE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("com.clickhouse.client.api.ClickHouseException", LOADER);

    @Nullable
    private static final Class<?> SERVER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("com.clickhouse.client.api.ServerException", LOADER);

    @Nullable
    private static final Class<?> CLIENT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("com.clickhouse.client.api.ClientException", LOADER);

    @Nullable
    private static final Class<?> CLIENT_MISCONFIGURATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("com.clickhouse.client.api.ClientMisconfigurationException", LOADER);

    @Nullable
    private static final Class<?> CONNECTION_INITIATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("com.clickhouse.client.api.ConnectionInitiationException", LOADER);

    @Nullable
    private static final Class<?> DATA_TRANSFER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("com.clickhouse.client.api.DataTransferException", LOADER);

    private static final Map<Integer, ClickHouseErrorType> EXACT_CODE_MAP = buildExactCodeMap();

    private static final Map<Integer, String> CODE_NAMES = buildCodeNames();

    public ClickHouseExceptionStrategy(@NonNull StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.CLICKHOUSE, stackHintExtractor);
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(CLICKHOUSE_EXCEPTION, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        ClickHouseErrorType type = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("clickhouse")
                .diagnosticMetadata(buildDiagnosticMetadata(cause))
                .build();
        details.add(info);

        return type.toResolution(details);
    }

    @NonNull
    private static ClickHouseErrorType classify(@NonNull Throwable cause) {
        // Группа 1: специфичные подтипы клиента (проверяются раньше базовых)
        if (ClassUtils.isInstanceOf(CLIENT_MISCONFIGURATION_EXCEPTION, cause)) {
            return ClickHouseErrorType.CLIENT_MISCONFIG;
        }
        if (ClassUtils.isInstanceOf(CONNECTION_INITIATION_EXCEPTION, cause)) {
            return ClickHouseErrorType.CONNECTION_INIT;
        }
        if (ClassUtils.isInstanceOf(DATA_TRANSFER_EXCEPTION, cause)) {
            return ClickHouseErrorType.DATA_TRANSFER;
        }

        // Группа 2: серверная ошибка по точному коду
        if (ClassUtils.isInstanceOf(SERVER_EXCEPTION, cause)) {
            Integer code = extractServerErrorCode(cause);
            if (code != null && code > 0) {
                ClickHouseErrorType byCode = EXACT_CODE_MAP.get(code);
                if (byCode != null) {
                    return byCode;
                }
            }

            // Fallback по транспортному HTTP-статусу из ServerException.
            Integer transportStatus = extractTransportProtocolCode(cause);
            if (transportStatus != null) {
                return classifyByHttpStatus(transportStatus);
            }
        }

        // Группа 3: generic ClientException без подтипа — сетевая/протокольная ошибка
        if (ClassUtils.isInstanceOf(CLIENT_EXCEPTION, cause)) {
            return ClickHouseErrorType.CLIENT;
        }

        return ClickHouseErrorType.GENERIC;
    }

    @NonNull
    private static ClickHouseErrorType classifyByHttpStatus(int transportStatus) {
        if (transportStatus >= 500) {
            return ClickHouseErrorType.SERVER_ERROR;
        }

        if (transportStatus == 429) {
            return ClickHouseErrorType.QUOTA_EXCEEDED;
        }

        if (transportStatus == 413) {
            return ClickHouseErrorType.PAYLOAD_TOO_LARGE;
        }

        if (transportStatus == 404) {
            return ClickHouseErrorType.NOT_FOUND;
        }

        if (transportStatus == 403 || transportStatus == 401) {
            return ClickHouseErrorType.ACCESS_DENIED;
        }

        if (transportStatus >= 400) {
            return ClickHouseErrorType.BAD_QUERY;
        }

        return ClickHouseErrorType.GENERIC;
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);
        builder.putTruncatedIfPresent("causeMessage", cause.getMessage(), MAX_CAUSE_MESSAGE_LENGTH);

        if (ClassUtils.isInstanceOf(SERVER_EXCEPTION, cause)) {
            enrichWithServerExceptionMetadata(cause, builder);
        }

        builder.withRootCause(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH, MAX_CAUSE_MESSAGE_LENGTH);

        return builder.
                withStackHint().
                build();
    }

    private void enrichWithServerExceptionMetadata(@NonNull Throwable cause,
                                                   @NonNull DiagnosticMetadataBuilder builder) {
        Integer code = extractServerErrorCode(cause);
        if (code != null && code > 0) {
            builder.put("chErrorCode", code.toString());
            String name = CODE_NAMES.get(code);
            if (name != null) {
                builder.put("chErrorCodeName", name);
            }
        }

        Integer transportStatus = extractTransportProtocolCode(cause);
        if (transportStatus != null && transportStatus > 0) {
            builder.put("chTransportStatus", transportStatus.toString());
        }

        // queryId — UUID-подобный идентификатор, не sensitive по своей природе;
        // критичен для поиска записи в system.query_log.
        ReflectionUtils.invokeMethod(cause, "getQueryId", queryId -> {
            if (queryId instanceof String s && !s.isBlank()) {
                builder.put("chQueryId", s);
            }
        });

        // Hint драйвера про retryability — для обнаружения расхождений
        // между классификацией стратегии и драйвера при обновлениях.
        ReflectionUtils.invokeMethod(cause, "isRetryable", retryable -> {
            if (retryable instanceof Boolean b) {
                builder.put("chServerRetryableHint", b.toString());
            }
        });
    }

    /**
     * Пробует извлечь серверный код через:
     * <p>1. {@code getCode()} (ServerException v2) и
     * <p>2. {@code getErrorCode()} (legacy fallback / inherited методы).
     */
    @Nullable
    private static Integer extractServerErrorCode(@NonNull Throwable cause) {
        Integer[] holder = {null};

        ReflectionUtils.invokeMethod(cause, "getCode", value -> {
            if (value instanceof Integer i) {
                holder[0] = i;
            }
        });

        if (holder[0] != null) {
            return holder[0];
        }

        ReflectionUtils.invokeMethod(cause, "getErrorCode", value -> {
            if (value instanceof Integer i) {
                holder[0] = i;
            }
        });

        return holder[0];
    }

    @Nullable
    private static Integer extractTransportProtocolCode(@NonNull Throwable cause) {
        Integer[] holder = {null};
        ReflectionUtils.invokeMethod(cause, "getTransportProtocolCode", value -> {
            if (value instanceof Integer i) {
                holder[0] = i;
            }
        });

        return holder[0];
    }

    @NonNull
    @SuppressWarnings("checkstyle:MethodLength")
    private static Map<Integer, ClickHouseErrorType> buildExactCodeMap() {
        Map<Integer, ClickHouseErrorType> m = new HashMap<>();

        // --- Authentication / Authorization (401 / 403) ---
        m.put(192, ClickHouseErrorType.AUTH_FAILED);            // UNKNOWN_USER
        m.put(193, ClickHouseErrorType.AUTH_FAILED);            // WRONG_PASSWORD
        m.put(194, ClickHouseErrorType.AUTH_FAILED);            // REQUIRED_PASSWORD
        m.put(516, ClickHouseErrorType.AUTH_FAILED);            // AUTHENTICATION_FAILED
        m.put(195, ClickHouseErrorType.ACCESS_DENIED);          // IP_ADDRESS_NOT_ALLOWED
        m.put(291, ClickHouseErrorType.ACCESS_DENIED);          // DATABASE_ACCESS_DENIED
        m.put(481, ClickHouseErrorType.ACCESS_DENIED);          // PATH_ACCESS_DENIED
        m.put(482, ClickHouseErrorType.ACCESS_DENIED);          // DICTIONARY_ACCESS_DENIED
        m.put(497, ClickHouseErrorType.ACCESS_DENIED);          // ACCESS_DENIED

        // --- Read-only / replica catching up (403, retryable per official driver) ---
        m.put(164, ClickHouseErrorType.READ_ONLY);              // READONLY
        m.put(242, ClickHouseErrorType.READ_ONLY);              // TABLE_IS_READ_ONLY
        m.put(472, ClickHouseErrorType.READ_ONLY);              // READONLY_SETTING

        // --- Not found (404) ---
        m.put(60,  ClickHouseErrorType.NOT_FOUND);              // UNKNOWN_TABLE
        m.put(81,  ClickHouseErrorType.NOT_FOUND);              // UNKNOWN_DATABASE

        // --- Bad query / programming errors (400) ---
        m.put(36,  ClickHouseErrorType.BAD_QUERY);              // BAD_ARGUMENTS
        m.put(47,  ClickHouseErrorType.BAD_QUERY);              // UNKNOWN_IDENTIFIER
        m.put(53,  ClickHouseErrorType.BAD_QUERY);              // TYPE_MISMATCH
        m.put(62,  ClickHouseErrorType.BAD_QUERY);              // SYNTAX_ERROR
        m.put(70,  ClickHouseErrorType.BAD_QUERY);              // CANNOT_CONVERT_TYPE
        m.put(80,  ClickHouseErrorType.BAD_QUERY);              // INCORRECT_QUERY
        m.put(115, ClickHouseErrorType.BAD_QUERY);              // UNKNOWN_SETTING
        m.put(215, ClickHouseErrorType.BAD_QUERY);              // NOT_AN_AGGREGATE
        m.put(408, ClickHouseErrorType.BAD_QUERY);              // BAD_REQUEST_PARAMETER
        m.put(456, ClickHouseErrorType.BAD_QUERY);              // UNKNOWN_QUERY_PARAMETER
        m.put(457, ClickHouseErrorType.BAD_QUERY);              // BAD_QUERY_PARAMETER
        m.put(471, ClickHouseErrorType.BAD_QUERY);              // INVALID_SETTING_VALUE

        // --- Bad data / parsing (422 Unprocessable Entity) ---
        m.put(6,   ClickHouseErrorType.BAD_DATA);               // CANNOT_PARSE_TEXT
        m.put(27,  ClickHouseErrorType.BAD_DATA);               // CANNOT_PARSE_INPUT_ASSERTION_FAILED
        m.put(38,  ClickHouseErrorType.BAD_DATA);               // CANNOT_PARSE_DATE
        m.put(41,  ClickHouseErrorType.BAD_DATA);               // CANNOT_PARSE_DATETIME
        m.put(43,  ClickHouseErrorType.BAD_DATA);               // ILLEGAL_TYPE_OF_ARGUMENT
        m.put(72,  ClickHouseErrorType.BAD_DATA);               // CANNOT_PARSE_NUMBER
        m.put(117, ClickHouseErrorType.BAD_DATA);               // INCORRECT_DATA
        m.put(246, ClickHouseErrorType.BAD_DATA);               // CORRUPTED_DATA
        m.put(469, ClickHouseErrorType.BAD_DATA);               // VIOLATED_CONSTRAINT

        // --- Conflict / already exists (409) ---
        m.put(57,  ClickHouseErrorType.CONFLICT);               // TABLE_ALREADY_EXISTS
        m.put(82,  ClickHouseErrorType.CONFLICT);               // DATABASE_ALREADY_EXISTS
        m.put(216, ClickHouseErrorType.CONFLICT);               // QUERY_WITH_SAME_ID_IS_ALREADY_RUNNING
        m.put(235, ClickHouseErrorType.CONFLICT);               // DUPLICATE_DATA_PART
        m.put(253, ClickHouseErrorType.CONFLICT);               // REPLICA_ALREADY_EXISTS
        m.put(256, ClickHouseErrorType.CONFLICT);               // PARTITION_ALREADY_EXISTS
        m.put(387, ClickHouseErrorType.CONFLICT);               // DICTIONARY_ALREADY_EXISTS
        m.put(442, ClickHouseErrorType.CONFLICT);               // BAD_DATABASE_FOR_TEMPORARY_TABLE
        m.put(504, ClickHouseErrorType.CONFLICT);               // FILE_ALREADY_EXISTS
        m.put(473, ClickHouseErrorType.DEADLOCK_AVOIDED);       // DEADLOCK_AVOIDED — retryable

        // --- Quotas / rate limits (429) ---
        m.put(201, ClickHouseErrorType.QUOTA_EXCEEDED);         // QUOTA_EXCEEDED
        m.put(290, ClickHouseErrorType.QUOTA_EXCEEDED);         // LIMIT_EXCEEDED
        m.put(364, ClickHouseErrorType.QUOTA_EXCEEDED);         // RECEIVED_ERROR_TOO_MANY_REQUESTS

        // --- Payload too large (413) ---
        m.put(158, ClickHouseErrorType.PAYLOAD_TOO_LARGE);      // TOO_MANY_ROWS
        m.put(161, ClickHouseErrorType.PAYLOAD_TOO_LARGE);      // TOO_MANY_COLUMNS
        m.put(191, ClickHouseErrorType.PAYLOAD_TOO_LARGE);      // SET_SIZE_LIMIT_EXCEEDED
        m.put(396, ClickHouseErrorType.PAYLOAD_TOO_LARGE);      // TOO_MANY_ROWS_OR_BYTES

        // --- Timeouts (504, retryable) ---
        m.put(159, ClickHouseErrorType.TIMEOUT);                // TIMEOUT_EXCEEDED
        m.put(160, ClickHouseErrorType.TIMEOUT);                // TOO_SLOW
        m.put(209, ClickHouseErrorType.TIMEOUT);                // SOCKET_TIMEOUT
        m.put(394, ClickHouseErrorType.TIMEOUT);                // QUERY_WAS_CANCELLED

        // --- Network / connection (503, retryable) ---
        m.put(198, ClickHouseErrorType.NETWORK);                // DNS_ERROR
        m.put(203, ClickHouseErrorType.NETWORK);                // NO_FREE_CONNECTION
        m.put(210, ClickHouseErrorType.NETWORK);                // NETWORK_ERROR
        m.put(236, ClickHouseErrorType.NETWORK);                // ABORTED
        m.put(279, ClickHouseErrorType.NETWORK);                // ALL_CONNECTION_TRIES_FAILED
        m.put(410, ClickHouseErrorType.NETWORK);                // EXTERNAL_SERVER_IS_NOT_RESPONDING
        m.put(519, ClickHouseErrorType.NETWORK);                // NO_REMOTE_SHARD_AVAILABLE

        // --- Resource exhaustion ---
        m.put(173, ClickHouseErrorType.MEMORY_NON_RETRYABLE);   // CANNOT_ALLOCATE_MEMORY
        m.put(241, ClickHouseErrorType.MEMORY_RETRYABLE);       // MEMORY_LIMIT_EXCEEDED
        m.put(243, ClickHouseErrorType.STORAGE_FULL);           // NOT_ENOUGH_SPACE

        // --- Server overloaded / backpressure (503, retryable) ---
        m.put(202, ClickHouseErrorType.OVERLOADED);             // TOO_MANY_SIMULTANEOUS_QUERIES
        m.put(252, ClickHouseErrorType.OVERLOADED);             // TOO_MANY_PARTS
        m.put(565, ClickHouseErrorType.OVERLOADED);             // TOO_MANY_PARTITIONS

        // --- Replication / distributed (503) ---
        m.put(234, ClickHouseErrorType.REPLICATION);            // NO_REPLICA_HAS_PART
        m.put(254, ClickHouseErrorType.REPLICATION);            // NO_ACTIVE_REPLICAS
        m.put(265, ClickHouseErrorType.REPLICATION);            // NO_AVAILABLE_REPLICA
        m.put(285, ClickHouseErrorType.REPLICATION);            // TOO_FEW_LIVE_REPLICAS
        m.put(286, ClickHouseErrorType.REPLICATION);            // UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE
        m.put(289, ClickHouseErrorType.REPLICATION);            // REPLICA_IS_NOT_IN_QUORUM
        m.put(369, ClickHouseErrorType.REPLICATION);            // ALL_REPLICAS_ARE_STALE
        m.put(415, ClickHouseErrorType.REPLICATION);            // ALL_REPLICAS_LOST
        m.put(999, ClickHouseErrorType.REPLICATION);            // KEEPER_EXCEPTION (ZooKeeper)

        // --- Insert outcome unknown (502, retryable iff idempotent) ---
        m.put(319, ClickHouseErrorType.INSERT_STATUS_UNKNOWN);  // UNKNOWN_STATUS_OF_INSERT

        // --- Server internal errors (500) ---
        m.put(49,  ClickHouseErrorType.LOGICAL_ERROR);          // LOGICAL_ERROR — серверный баг
        m.put(425, ClickHouseErrorType.SYSTEM_ERROR);           // SYSTEM_ERROR — retryable per driver
        m.put(391, ClickHouseErrorType.INTERNAL);               // EXTERNAL_LIBRARY_ERROR
        m.put(528, ClickHouseErrorType.INTERNAL);               // CASSANDRA_INTERNAL_ERROR
        m.put(532, ClickHouseErrorType.INTERNAL);               // LDAP_ERROR
        m.put(555, ClickHouseErrorType.INTERNAL);               // ROCKSDB_ERROR
        m.put(568, ClickHouseErrorType.INTERNAL);               // RAFT_ERROR
        m.put(576, ClickHouseErrorType.INTERNAL);               // KERBEROS_ERROR
        m.put(591, ClickHouseErrorType.INTERNAL);               // SQLITE_ENGINE_ERROR
        m.put(593, ClickHouseErrorType.INTERNAL);               // ZERO_COPY_REPLICATION_ERROR
        m.put(614, ClickHouseErrorType.INTERNAL);               // POSTGRESQL_CONNECTION_FAILURE
        m.put(619, ClickHouseErrorType.INTERNAL);               // POSTGRESQL_REPLICATION_INTERNAL_ERROR

        return Map.copyOf(m);
    }

    @NonNull
    @SuppressWarnings("checkstyle:MethodLength")
    private static Map<Integer, String> buildCodeNames() {
        // Имена выровнены с src/Common/ErrorCodes.cpp на момент актуальной master-ветки.
        // Включены только те коды, которые попадают в EXACT_CODE_MAP.
        Map<Integer, String> m = new HashMap<>();
        m.put(6,   "CANNOT_PARSE_TEXT");
        m.put(27,  "CANNOT_PARSE_INPUT_ASSERTION_FAILED");
        m.put(36,  "BAD_ARGUMENTS");
        m.put(38,  "CANNOT_PARSE_DATE");
        m.put(41,  "CANNOT_PARSE_DATETIME");
        m.put(43,  "ILLEGAL_TYPE_OF_ARGUMENT");
        m.put(47,  "UNKNOWN_IDENTIFIER");
        m.put(49,  "LOGICAL_ERROR");
        m.put(53,  "TYPE_MISMATCH");
        m.put(57,  "TABLE_ALREADY_EXISTS");
        m.put(60,  "UNKNOWN_TABLE");
        m.put(62,  "SYNTAX_ERROR");
        m.put(70,  "CANNOT_CONVERT_TYPE");
        m.put(72,  "CANNOT_PARSE_NUMBER");
        m.put(80,  "INCORRECT_QUERY");
        m.put(81,  "UNKNOWN_DATABASE");
        m.put(82,  "DATABASE_ALREADY_EXISTS");
        m.put(115, "UNKNOWN_SETTING");
        m.put(117, "INCORRECT_DATA");
        m.put(158, "TOO_MANY_ROWS");
        m.put(159, "TIMEOUT_EXCEEDED");
        m.put(160, "TOO_SLOW");
        m.put(161, "TOO_MANY_COLUMNS");
        m.put(164, "READONLY");
        m.put(173, "CANNOT_ALLOCATE_MEMORY");
        m.put(191, "SET_SIZE_LIMIT_EXCEEDED");
        m.put(192, "UNKNOWN_USER");
        m.put(193, "WRONG_PASSWORD");
        m.put(194, "REQUIRED_PASSWORD");
        m.put(195, "IP_ADDRESS_NOT_ALLOWED");
        m.put(198, "DNS_ERROR");
        m.put(201, "QUOTA_EXCEEDED");
        m.put(202, "TOO_MANY_SIMULTANEOUS_QUERIES");
        m.put(203, "NO_FREE_CONNECTION");
        m.put(209, "SOCKET_TIMEOUT");
        m.put(210, "NETWORK_ERROR");
        m.put(215, "NOT_AN_AGGREGATE");
        m.put(216, "QUERY_WITH_SAME_ID_IS_ALREADY_RUNNING");
        m.put(234, "NO_REPLICA_HAS_PART");
        m.put(235, "DUPLICATE_DATA_PART");
        m.put(236, "ABORTED");
        m.put(241, "MEMORY_LIMIT_EXCEEDED");
        m.put(242, "TABLE_IS_READ_ONLY");
        m.put(243, "NOT_ENOUGH_SPACE");
        m.put(246, "CORRUPTED_DATA");
        m.put(252, "TOO_MANY_PARTS");
        m.put(253, "REPLICA_ALREADY_EXISTS");
        m.put(254, "NO_ACTIVE_REPLICAS");
        m.put(256, "PARTITION_ALREADY_EXISTS");
        m.put(265, "NO_AVAILABLE_REPLICA");
        m.put(279, "ALL_CONNECTION_TRIES_FAILED");
        m.put(285, "TOO_FEW_LIVE_REPLICAS");
        m.put(286, "UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE");
        m.put(289, "REPLICA_IS_NOT_IN_QUORUM");
        m.put(290, "LIMIT_EXCEEDED");
        m.put(291, "DATABASE_ACCESS_DENIED");
        m.put(319, "UNKNOWN_STATUS_OF_INSERT");
        m.put(364, "RECEIVED_ERROR_TOO_MANY_REQUESTS");
        m.put(369, "ALL_REPLICAS_ARE_STALE");
        m.put(387, "DICTIONARY_ALREADY_EXISTS");
        m.put(391, "EXTERNAL_LIBRARY_ERROR");
        m.put(394, "QUERY_WAS_CANCELLED");
        m.put(396, "TOO_MANY_ROWS_OR_BYTES");
        m.put(408, "BAD_REQUEST_PARAMETER");
        m.put(410, "EXTERNAL_SERVER_IS_NOT_RESPONDING");
        m.put(415, "ALL_REPLICAS_LOST");
        m.put(425, "SYSTEM_ERROR");
        m.put(442, "BAD_DATABASE_FOR_TEMPORARY_TABLE");
        m.put(456, "UNKNOWN_QUERY_PARAMETER");
        m.put(457, "BAD_QUERY_PARAMETER");
        m.put(469, "VIOLATED_CONSTRAINT");
        m.put(471, "INVALID_SETTING_VALUE");
        m.put(472, "READONLY_SETTING");
        m.put(473, "DEADLOCK_AVOIDED");
        m.put(481, "PATH_ACCESS_DENIED");
        m.put(482, "DICTIONARY_ACCESS_DENIED");
        m.put(497, "ACCESS_DENIED");
        m.put(504, "FILE_ALREADY_EXISTS");
        m.put(516, "AUTHENTICATION_FAILED");
        m.put(519, "NO_REMOTE_SHARD_AVAILABLE");
        m.put(528, "CASSANDRA_INTERNAL_ERROR");
        m.put(532, "LDAP_ERROR");
        m.put(555, "ROCKSDB_ERROR");
        m.put(565, "TOO_MANY_PARTITIONS");
        m.put(568, "RAFT_ERROR");
        m.put(576, "KERBEROS_ERROR");
        m.put(591, "SQLITE_ENGINE_ERROR");
        m.put(593, "ZERO_COPY_REPLICATION_ERROR");
        m.put(614, "POSTGRESQL_CONNECTION_FAILURE");
        m.put(619, "POSTGRESQL_REPLICATION_INTERNAL_ERROR");
        m.put(999, "KEEPER_EXCEPTION");
        return Map.copyOf(m);
    }

    enum ClickHouseErrorType implements ErrorClassification {

        // --- Authentication / authorization ---

        AUTH_FAILED(
                HttpStatus.UNAUTHORIZED,
                Retryability.NOT_RETRYABLE,
                "Authentication with the ClickHouse server failed."
        ),

        ACCESS_DENIED(
                HttpStatus.FORBIDDEN,
                Retryability.NOT_RETRYABLE,
                "Access to the ClickHouse resource was denied due to insufficient permissions."
        ),

        READ_ONLY(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The ClickHouse target is read-only and the operation cannot be applied."
        ),

        // --- Resource resolution ---

        NOT_FOUND(
                HttpStatus.NOT_FOUND,
                Retryability.NOT_RETRYABLE,
                "The referenced ClickHouse table or database does not exist."
        ),

        // --- Client request errors ---

        BAD_QUERY(
                HttpStatus.BAD_REQUEST,
                Retryability.NOT_RETRYABLE,
                "The ClickHouse query is invalid or refers to unknown identifiers."
        ),

        BAD_DATA(
                HttpStatus.UNPROCESSABLE_ENTITY,
                Retryability.NOT_RETRYABLE,
                "The data provided to the ClickHouse server cannot be parsed or violates a constraint."
        ),

        // --- Conflict ---

        CONFLICT(
                HttpStatus.CONFLICT,
                Retryability.NOT_RETRYABLE,
                "The ClickHouse object already exists or is in use by another concurrent operation."
        ),

        DEADLOCK_AVOIDED(
                HttpStatus.CONFLICT,
                Retryability.RETRYABLE,
                "A potential deadlock was detected and the operation was aborted. Retry the operation."
        ),

        // --- Limits / quotas ---

        QUOTA_EXCEEDED(
                HttpStatus.TOO_MANY_REQUESTS,
                Retryability.RETRYABLE,
                "The ClickHouse quota or rate limit has been exceeded. Retry after backing off."
        ),

        PAYLOAD_TOO_LARGE(
                HttpStatus.PAYLOAD_TOO_LARGE,
                Retryability.NOT_RETRYABLE,
                "The query result or input batch exceeds the configured ClickHouse size limit."
        ),

        // --- Timeouts ---

        TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "The ClickHouse operation did not complete within the configured timeout."
        ),

        // --- Network ---

        NETWORK(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "A network error occurred while communicating with the ClickHouse server."
        ),

        /**
         * @implNote retryable=true (per official driver list 241): причина —
         * временный пик нагрузки или GC. После backoff повтор обычно успешен.
         */
        MEMORY_RETRYABLE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The ClickHouse server reached a memory limit while processing the query. Retry after a short delay."
        ),

        /**
         * @implNote retryable=false: исчерпан физический heap процесса —
         * единственный путь восстановления требует вмешательства SRE
         * (рестарт инстанса, capacity scaling).
         */
        MEMORY_NON_RETRYABLE(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The ClickHouse server failed to allocate memory for the operation."
        ),

        STORAGE_FULL(
                HttpStatus.INSUFFICIENT_STORAGE,
                Retryability.NOT_RETRYABLE,
                "The ClickHouse server has insufficient disk space to complete the operation."
        ),

        // --- Backpressure / overloaded ---

        OVERLOADED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The ClickHouse server is temporarily overloaded. Retry after backing off."
        ),

        REPLICATION(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The ClickHouse replica set or coordinator (Keeper/ZooKeeper) is reconfiguring or unavailable. Retry after a short delay."
        ),

        // --- Insert status unknown ---

        /**
         * @implNote retryable=true, но <b>только для идемпотентных вставок</b>
         * (использующих {@code insert_deduplication_token} или
         * {@code insert_quorum} с {@code insert_quorum_parallel=0}). Для
         * неидемпотентных вставок повтор может привести к дублированию данных
         * на стороне сервера. Аналог {@code MongoErrorType.WRITE_CONCERN}.
         */
        INSERT_STATUS_UNKNOWN(
                HttpStatus.BAD_GATEWAY,
                Retryability.RETRYABLE,
                "The status of the ClickHouse insert is unknown. Retry only if the insert is idempotent (deduplication token or quorum-based)."
        ),

        // --- Generic server-side ---

        SERVER_ERROR(
                HttpStatus.BAD_GATEWAY,
                Retryability.NOT_RETRYABLE,
                "The ClickHouse server returned an error while processing the request."
        ),

        /**
         * @implNote retryable=true (per official driver list 425): системные
         * ошибки уровня OS обычно транзиентны (file descriptor exhaustion, inode pressure).
         */
        SYSTEM_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.RETRYABLE,
                "The ClickHouse server encountered a transient system error. Retry after a short delay."
        ),

        LOGICAL_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The ClickHouse server encountered an internal logical error."
        ),

        INTERNAL(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An internal ClickHouse subsystem error occurred."
        ),

        // --- Client-side ---

        CLIENT_MISCONFIG(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The ClickHouse client is misconfigured."
        ),

        CONNECTION_INIT(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "Failed to establish a connection to the ClickHouse server."
        ),

        DATA_TRANSFER(
                HttpStatus.BAD_GATEWAY,
                Retryability.RETRYABLE,
                "A data transfer error occurred while reading from or writing to the ClickHouse server."
        ),

        CLIENT(
                HttpStatus.BAD_GATEWAY,
                Retryability.NOT_RETRYABLE,
                "The ClickHouse client failed to process the response."
        ),

        // --- Catch-all ---

        GENERIC(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An unexpected ClickHouse error occurred."
        );

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        ClickHouseErrorType(@NonNull HttpStatus httpStatus,
                            @NonNull Retryability retryability,
                            @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @Override
        @NonNull
        public HttpStatus httpStatus() {
            return httpStatus;
        }

        @Override
        @NonNull
        public Retryability retryability() {
            return retryability;
        }

        @Override
        @NonNull
        public String message() {
            return message;
        }
    }
}
