package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
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

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Стратегия обработки исключений HikariCP connection pool как первопричины бизнес-исключений.
 *
 * <p>Все HikariCP и Spring JDBC классы загружаются через reflection — стратегия
 * компилируется и загружается без наличия {@code com.zaxxer.hikari:HikariCP}
 * или {@code spring-jdbc} на classpath. Условие активации обеспечивается
 * {@code @ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")}
 * в auto-configuration.
 *
 * <h2>Обрабатываемые сценарии</h2>
 * <pre>
 * (A) Pool exhaustion / timeout — 503, retryable=true:
 *     CannotGetJdbcConnectionException
 *       └── SQLTransientConnectionException    ← HikariCP "timed out after Xms"
 *
 * (B) Pool initialization failure — 503, retryable=false:
 *     CannotGetJdbcConnectionException
 *       └── HikariPool$PoolInitializationException
 *     или напрямую:
 *     HikariPool$PoolInitializationException
 *
 * (C) Generic HikariCP connection failure — 503, retryable=true:
 *     DataAccessResourceFailureException
 *       └── SQLTransientConnectionException
 *
 * (D) Raw JDBC (без Spring wrapper) — 503, retryable=true:
 *     SQLTransientConnectionException напрямую
 *
 * (E) Pool suspended — 503, retryable=false:
 *     HikariPool$PoolSuspendedException
 *     или SQLTransientConnectionException с "suspended" в сообщении
 *     (когда {@code allowPoolSuspension=true} и пул приостановлен через JMX)
 *
 * (F) Pool shutdown — 503, retryable=false:
 *     SQLTransientConnectionException с "has been closed" в сообщении
 * </pre>
 *
 * <p>{@code CannotGetJdbcConnectionException} без HikariCP-специфичной cause
 * намеренно <b>не матчится</b>: стратегия не захватывает соединения от сторонних
 * пулов (c3p0, DBCP2, Tomcat JDBC) — они остаются за {@link DataAccessExceptionStrategy}.
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <p>Поля {@code diagnosticMetadata} (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения ({@code getName()}).</li>
 *   <li>{@code causeMessage} — сообщение непосредственной причины (truncated до 300 символов).</li>
 *   <li>{@code rootCauseClass} — FQCN глубинной причины (если отличается от непосредственной).</li>
 *   <li>{@code rootCauseMessage} — сообщение глубинной причины (если отличается от causeMessage).</li>
 *   <li>{@code waitTimeMs} — фактическое время ожидания из сообщения HikariCP
 *       (только для {@code POOL_TIMEOUT}).</li>
 *   <li>{@code sqlState} — SQL State код из {@code SQLException} в cause-chain (глубина до 5).</li>
 *   <li>{@code sqlErrorCode} — vendor-specific error code ({@code 0} не включается).</li>
 *   <li>{@code stackHint} — сжатый стек-трейс из прикладных фреймов.</li>
 * </ul>
 *
 * <h3>Pool config (snapshot)</h3>
 * <ul>
 *   <li>{@code poolName} — имя пула из конфигурации.</li>
 *   <li>{@code jdbcVendor} — тип СУБД из JDBC URL (только схема, e.g. {@code jdbc:postgresql}).</li>
 *   <li>{@code poolMaxSize} — максимальный размер пула ({@code maximumPoolSize}).</li>
 *   <li>{@code poolMinIdle} — минимальное число idle-соединений ({@code minimumIdle}).</li>
 *   <li>{@code connectionTimeoutMs} — настроенный {@code connectionTimeout} (мс).</li>
 *   <li>{@code keepaliveTimeMs} — настроенный {@code keepaliveTime} (мс, включается при &gt; 0).</li>
 *   <li>{@code leakDetectionThresholdMs} — порог обнаружения утечек (мс, включается при &gt; 0).</li>
 *   <li>{@code idleTimeoutMs} — время жизни idle-соединения (мс).</li>
 *   <li>{@code maxLifetimeMs} — максимальное время жизни соединения (мс).</li>
 *   <li>{@code validationTimeoutMs} — таймаут проверки живости соединения (мс).</li>
 * </ul>
 *
 * <h3>Pool runtime (MXBean snapshot в момент ошибки)</h3>
 * <ul>
 *   <li>{@code poolActiveConnections} — активные соединения.</li>
 *   <li>{@code poolIdleConnections} — свободные соединения.</li>
 *   <li>{@code poolTotalConnections} — всего соединений.</li>
 *   <li>{@code poolAwaitingThreads} — потоки в очереди за соединением.</li>
 * </ul>
 *
 * <h3>Безопасность (CWE-209)</h3>
 * <p>Полный JDBC URL, hostname и credentials намеренно не включаются в metadata.
 * {@code jdbcVendor} содержит только схему протокола (e.g. {@code jdbc:postgresql}),
 * не раскрывая инфраструктурных деталей. {@code sqlState} — стандартизированный
 * диагностический код, не содержащий sensitive data.</p>
 *
 * <h2>Multi-DataSource</h2>
 * <p>При наличии нескольких DataSource бинов стратегия получает {@code @Primary}.
 * Для multi-tenant / dynamic pool сценариев нужно переопределить
 * {@link #resolveHikariDataSource(DataSource)}.
 */
public class HikariCpExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;
    private static final int MAX_PROXY_DEPTH = 5;

    private static final Pattern TIMEOUT_MS_PATTERN = Pattern.compile("timed out after (\\d+)ms", Pattern.CASE_INSENSITIVE);

    private static final ClassLoader LOADER = HikariCpExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> HIKARI_DATASOURCE =
            ReflectionUtils.loadOptionalClass("com.zaxxer.hikari.HikariDataSource", LOADER);

    @Nullable
    private static final Class<?> HIKARI_POOL_INIT =
            ReflectionUtils.loadOptionalClass("com.zaxxer.hikari.pool.HikariPool$PoolInitializationException", LOADER);

    @Nullable
    private static final Class<?> HIKARI_POOL_SUSPENDED =
            ReflectionUtils.loadOptionalClass("com.zaxxer.hikari.pool.HikariPool$PoolSuspendedException", LOADER);

    @Nullable
    private static final Class<?> CANNOT_GET_JDBC_CONNECTION =
            ReflectionUtils.loadOptionalClass("org.springframework.jdbc.CannotGetJdbcConnectionException", LOADER);

    @Nullable
    private static final Class<?> DATA_ACCESS_RESOURCE_FAILURE =
            ReflectionUtils.loadOptionalClass("org.springframework.dao.DataAccessResourceFailureException", LOADER);

    @Nullable
    private static final Class<?> DATASOURCE_PROXY =
            ReflectionUtils.loadOptionalClass("org.springframework.jdbc.datasource.DataSourceProxy", LOADER);

    @Nullable
    private final Object hikariDataSource;

    public HikariCpExceptionStrategy(@Nullable DataSource dataSource, StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.HIKARI, stackHintExtractor);
        this.hikariDataSource = resolveHikariDataSource(dataSource);
    }

    /**
     * Все HikariCP-исключения логируются как ERROR.
     *
     * <p>Pool exhaustion, initialization failure, shutdown и suspended — инфраструктурные
     * проблемы, требующие немедленного внимания SRE (capacity planning, leak detection,
     * database health). Даже retryable-типы ({@code POOL_TIMEOUT}, {@code CONNECTION_FAILED})
     * сигнализируют о системной проблеме, а не о единичном transient-событии.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.ERROR;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        if (cause instanceof SQLTransientConnectionException) {
            return true;
        }

        if (isPoolInitException(cause)) {
            return true;
        }

        if (isPoolSuspendedException(cause)) {
            return true;
        }

        if (isCannotGetJdbcConnection(cause)) {
            return isHikariSpecificCause(cause.getCause());
        }

        if (isDataAccessResourceFailure(cause)) {
            return isHikariSpecificCause(cause.getCause());
        }

        return false;
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        HikariErrorType errorType = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (errorType.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(errorType.name())
                .domain("connection-pool")
                .diagnosticMetadata(buildDiagnosticMetadata(cause, errorType))
                .build();
        details.add(info);

        return errorType.toResolution(details);
    }

    @NonNull
    private HikariErrorType classify(@NonNull Throwable cause) {
        if (isPoolInitException(cause)) {
            return HikariErrorType.POOL_INIT_FAILED;
        }

        if (isPoolSuspendedException(cause)) {
            return HikariErrorType.POOL_SUSPENDED;
        }

        if (isCannotGetJdbcConnection(cause) || isDataAccessResourceFailure(cause)) {
            Throwable nested = cause.getCause();
            if (isPoolInitException(nested)) {
                return HikariErrorType.POOL_INIT_FAILED;
            }

            if (isPoolSuspendedException(nested)) {
                return HikariErrorType.POOL_SUSPENDED;
            }

            if (nested instanceof SQLTransientConnectionException stce) {
                return classifyTransientConnection(stce);
            }

            return HikariErrorType.CONNECTION_FAILED;
        }

        if (cause instanceof SQLTransientConnectionException stce) {
            return classifyTransientConnection(stce);
        }

        return HikariErrorType.CONNECTION_FAILED;
    }

    @NonNull
    private static HikariErrorType classifyTransientConnection(@NonNull SQLTransientConnectionException e) {
        String msg = e.getMessage();
        if (StringUtils.hasText(msg)) {
            if (msg.contains("has been closed")) {
                return HikariErrorType.POOL_SHUTDOWN;
            }

            if (msg.contains("suspended")) {
                return HikariErrorType.POOL_SUSPENDED;
            }
        }

        return HikariErrorType.POOL_TIMEOUT;
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull HikariErrorType errorType) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        Throwable immediateCause = (cause.getCause() != null) ? cause.getCause() : cause;
        String causeMessage = immediateCause.getMessage();
        builder.putTruncatedIfPresent("causeMessage", causeMessage, MAX_CAUSE_MESSAGE_LENGTH);

        builder.withRootCause(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH, MAX_CAUSE_MESSAGE_LENGTH);

        if (errorType == HikariErrorType.POOL_TIMEOUT) {
            String waitTime = extractWaitTimeMs(causeMessage);
            if (waitTime == null) {
                waitTime = extractWaitTimeMs(cause.getMessage());
            }

            if (waitTime != null) {
                builder.put("waitTimeMs", waitTime);
            }
        }

        if (hikariDataSource != null) {
            enrichWithPoolMetrics(hikariDataSource, builder);
        }

        enrichWithSqlDetails(cause, builder);

        return builder.withStackHint().build();
    }

    @Nullable
    private static String extractWaitTimeMs(@Nullable String msg) {
        if (msg == null) {
            return null;
        }

        Matcher m = TIMEOUT_MS_PATTERN.matcher(msg);
        return m.find() ? m.group(1) : null;
    }

    private static void enrichWithSqlDetails(@NonNull Throwable cause,
                                             @NonNull DiagnosticMetadataBuilder builder) {
        Throwable current = cause;

        for (int i = 0; (i < CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH) && (current != null); i++) {
            if (current instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if (StringUtils.hasText(sqlState)) {
                    builder.put("sqlState", sqlState);
                }

                int errorCode = sqlEx.getErrorCode();
                if (errorCode != 0) {
                    builder.put("sqlErrorCode", String.valueOf(errorCode));
                }

                break;
            }

            current = current.getCause();
        }
    }

    private static void enrichWithPoolMetrics(@NonNull Object ds,
                                              @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(ds, "getPoolName", poolName -> {
            if (poolName instanceof String s && StringUtils.hasText(s)) {
                builder.put("poolName", s);
            }
        });

        ReflectionUtils.invokeMethod(ds, "getJdbcUrl", url -> {
            if (url instanceof String s && StringUtils.hasText(s)) {
                builder.put("jdbcVendor", sanitizeJdbcUrl(s));
            }
        });

        ReflectionUtils.invokeMethod(ds, "getMaximumPoolSize", maxSize -> {
            if (maxSize instanceof Integer i) {
                builder.put("poolMaxSize", i.toString());
            }
        });

        ReflectionUtils.invokeMethod(ds, "getMinimumIdle", minIdle -> {
            if (minIdle instanceof Integer i) {
                builder.put("poolMinIdle", i.toString());
            }
        });

        ReflectionUtils.invokeMethod(ds, "getConnectionTimeout", timeout -> {
            if (timeout instanceof Long l) {
                builder.put("connectionTimeoutMs", l.toString());
            }
        });

        ReflectionUtils.invokeMethod(ds, "getLeakDetectionThreshold", leakThreshold -> {
            if (leakThreshold instanceof Long l && l > 0) {
                builder.put("leakDetectionThresholdMs", l.toString());
            }
        });

        ReflectionUtils.invokeMethod(ds, "getIdleTimeout", idleTimeout -> {
            if (idleTimeout instanceof Long l) {
                builder.put("idleTimeoutMs", l.toString());
            }
        });

        ReflectionUtils.invokeMethod(ds, "getMaxLifetime", maxLifetime -> {
            if (maxLifetime instanceof Long l) {
                builder.put("maxLifetimeMs", l.toString());
            }
        });

        ReflectionUtils.invokeMethod(ds, "getKeepaliveTime", keepalive -> {
            if (keepalive instanceof Long l && l > 0) {
                builder.put("keepaliveTimeMs", l.toString());
            }
        });

        ReflectionUtils.invokeMethod(ds, "getValidationTimeout", validationTimeout -> {
            if (validationTimeout instanceof Long l) {
                builder.put("validationTimeoutMs", l.toString());
            }
        });

        ReflectionUtils.invokeMethod(ds, "getHikariPoolMXBean", mxBean -> {
            if (mxBean == null) {
                return;
            }

            ReflectionUtils.invokeMethod(mxBean, "getActiveConnections", active -> {
                if (active instanceof Integer i) {
                    builder.put("poolActiveConnections", i.toString());
                }
            });

            ReflectionUtils.invokeMethod(mxBean, "getIdleConnections", idle -> {
                if (idle instanceof Integer i) {
                    builder.put("poolIdleConnections", i.toString());
                }
            });

            ReflectionUtils.invokeMethod(mxBean, "getTotalConnections", total -> {
                if (total instanceof Integer i) {
                    builder.put("poolTotalConnections", i.toString());
                }
            });

            ReflectionUtils.invokeMethod(mxBean, "getThreadsAwaitingConnection", waiting -> {
                if (waiting instanceof Integer i) {
                    builder.put("poolAwaitingThreads", i.toString());
                }
            });
        });
    }

    /**
     * Извлекает только схему протокола из JDBC URL (CWE-209 safe).
     *
     * <p>Примеры: {@code jdbc:postgresql://host/db} → {@code jdbc:postgresql},
     * {@code jdbc:h2:mem:test} → {@code jdbc:h2}.
     */
    @NonNull
    private static String sanitizeJdbcUrl(@NonNull String jdbcUrl) {
        int schemeEnd = jdbcUrl.indexOf("://");
        if (schemeEnd > 0) {
            return jdbcUrl.substring(0, schemeEnd);
        }

        int firstColon = jdbcUrl.indexOf(':');
        if (firstColon > 0) {
            int secondColon = jdbcUrl.indexOf(':', firstColon + 1);
            if (secondColon > 0) {
                return jdbcUrl.substring(0, secondColon);
            }
        }

        return jdbcUrl;
    }

    private static boolean isPoolInitException(@Nullable Throwable cause) {
        return ClassUtils.isInstanceOf(HIKARI_POOL_INIT, cause);
    }

    private static boolean isPoolSuspendedException(@Nullable Throwable cause) {
        return ClassUtils.isInstanceOf(HIKARI_POOL_SUSPENDED, cause);
    }

    private static boolean isCannotGetJdbcConnection(@Nullable Throwable cause) {
        return ClassUtils.isInstanceOf(CANNOT_GET_JDBC_CONNECTION, cause);
    }

    private static boolean isDataAccessResourceFailure(@Nullable Throwable cause) {
        return ClassUtils.isInstanceOf(DATA_ACCESS_RESOURCE_FAILURE, cause);
    }

    private static boolean isHikariSpecificCause(@Nullable Throwable cause) {
        if (cause == null) {
            return false;
        }

        return cause instanceof SQLTransientConnectionException
                || isPoolInitException(cause)
                || isPoolSuspendedException(cause);
    }

    /**
     * Рекурсивно разворачивает цепочку {@code DataSourceProxy} до {@code HikariDataSource}.
     *
     * <p>Поддерживает multi-layer proxy chains (типичные в enterprise):
     * {@code TransactionAwareDataSourceProxy → LazyConnectionDataSourceProxy → HikariDataSource}.
     * Глубина ограничена {@link #MAX_PROXY_DEPTH} для защиты от циклических ссылок.
     */
    @Nullable
    protected static Object resolveHikariDataSource(@Nullable DataSource dataSource) {
        if (dataSource == null || HIKARI_DATASOURCE == null) {
            return null;
        }

        Object current = dataSource;
        for (int i = 0; i < MAX_PROXY_DEPTH && current != null; i++) {
            if (HIKARI_DATASOURCE.isInstance(current)) {
                return current;
            }

            if (!ClassUtils.isInstanceOf(DATASOURCE_PROXY, current)) {
                break;
            }

            final Object[] holder = new Object[1];
            final Object target = current;
            ReflectionUtils.invokeMethod(target, "getTargetDataSource", t -> holder[0] = t);
            current = holder[0];
        }

        return null;
    }

    private enum HikariErrorType implements ErrorClassification {
        POOL_TIMEOUT(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The database connection pool is exhausted. "
                        + "All connections are in use; the request timed out "
                        + "waiting for an available connection."
        ),

        POOL_INIT_FAILED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The database connection pool failed to initialize. "
                        + "The database may be unreachable or the configuration may be incorrect."
        ),

        /**
         * Пул приостановлен через JMX ({@code allowPoolSuspension=true}).
         *
         * <p>{@code retryable=false}: момент resume непредсказуем для клиента.
         * После resume пул автоматически начнёт обслуживать запросы —
         * повторная попытка на уровне клиента семантически корректна,
         * но без гарантированного SLA на время resume {@code RetryInfoDetail} не отдаётся.
         */
        POOL_SUSPENDED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The database connection pool is currently suspended and cannot serve requests."
        ),

        POOL_SHUTDOWN(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The database connection pool has been shut down and cannot serve new requests."
        ),

        CONNECTION_FAILED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "Unable to obtain a database connection from the pool."
        );

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        HikariErrorType(@NonNull HttpStatus httpStatus,
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
