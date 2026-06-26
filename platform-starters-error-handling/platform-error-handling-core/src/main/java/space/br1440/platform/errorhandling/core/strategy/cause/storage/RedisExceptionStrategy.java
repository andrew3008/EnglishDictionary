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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Стратегия обработки исключений Redis драйверов: Lettuce, Jedis и Spring Data Redis
 * как первопричины бизнес-исключений.
 *
 * <p>Перехватывает исключения от всех основных Redis-клиентов:
 * <ul>
 *   <li><b>Lettuce</b> — прямой доступ к {@code io.lettuce.core.RedisClient} / {@code RedisClusterClient};</li>
 *   <li><b>Jedis</b> — прямой доступ к {@code redis.clients.jedis.*} (legacy, Redisson fallback);</li>
 *   <li><b>Spring Data Redis</b> — обёртки {@code RedisConnectionFailureException}, {@code RedisSystemException};</li>
 *   <li>Reactive pipeline {@code io.lettuce.core.api.reactive.*} — исключения не транслируются;</li>
 *   <li>Lua-скрипты ({@code RedisTemplate.execute(RedisScript)}) с необёрнутыми ошибками.</li>
 * </ul>
 *
 * <h2>Поддерживаемые драйверы и иерархии исключений</h2>
 *
 * <h3>Lettuce ({@code io.lettuce.core})</h3>
 * <pre>
 *   RedisException (base)
 *   ├── RedisConnectionException          → 503 Service Unavailable (retryable)
 *   ├── RedisCommandTimeoutException     → 504 Gateway Timeout (retryable)
 *   ├── RedisCommandExecutionException   → 502 Bad Gateway
 *   ├── cluster.RedisClusterException    → 503 Service Unavailable (retryable)
 *   └── (queue overflow)                 → 503 Service Unavailable (retryable, OVERLOADED)
 * </pre>
 *
 * <h3>Jedis ({@code redis.clients.jedis.exceptions})</h3>
 * <pre>
 *   JedisException (base)
 *   ├── JedisConnectionException              → 503 Service Unavailable (retryable)
 *   ├── JedisNoReachableClusterNodeException  → 503 Service Unavailable (retryable)
 *   ├── JedisExhaustedPoolException           → 503 Service Unavailable (retryable, OVERLOADED)
 *   ├── JedisBusyException                    → 503 Service Unavailable (retryable, OVERLOADED)
 *   ├── JedisClusterException                 → 503 Service Unavailable (retryable)
 *   ├── JedisDataException                    → 502 Bad Gateway
 *   │   ├── JedisAccessControlException       → 403 Forbidden (ACL, Jedis ≥ 3.2)
 *   │   ├── JedisNoScriptException            → 502 Bad Gateway
 *   │   └── JedisValidationException          → 502 Bad Gateway
 *   └── (прочие подклассы)                    → 503 Service Unavailable
 * </pre>
 *
 * <h3>Spring Data Redis ({@code org.springframework.data.redis})</h3>
 * <pre>
 *   RedisConnectionFailureException → 503 Service Unavailable (retryable)
 *   RedisSystemException            → 503 Service Unavailable
 * </pre>
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <p>Поля {@code diagnosticMetadata} (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения ({@code getName()}).</li>
 *   <li>{@code cacheProvider} — всегда {@code REDIS}.</li>
 *   <li>{@code redisDriver} — {@code LETTUCE|JEDIS|SPRING_DATA_REDIS}.</li>
 *   <li>{@code causeMessage} — сообщение исключения (truncated до 300 символов).</li>
 *   <li>{@code rootCauseClass}, {@code rootCauseMessage} — глубинная причина (если отличается от immediate).</li>
 *   <li>{@code stackHint} — сжатый стек-трейс из прикладных фреймов.</li>
 * </ul>
 *
 * <h3>Безопасность (CWE-209)</h3>
 * <p>{@code causeMessage} truncated до 300 символов, может содержать topology details
 * (hostname:port) — допустимо для SRE-only {@code diagnosticMetadata}.
 * Полное тело ответа Redis и credentials намеренно <b>не включаются</b>.</p>
 */
public class RedisExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;
    private static final Pattern REDIS_PREFIX_PATTERN = Pattern.compile("[A-Z_]+");

    /**
     * Lettuce: "Command timed out after 6000 millisecond(s)".
     */
    private static final Pattern TIMEOUT_MS_PATTERN =
            Pattern.compile("(\\d+)\\s*millisecond", Pattern.CASE_INSENSITIVE);

    /**
     * Lettuce 6.x: "Command timed out after 6 second(s)".
     */
    private static final Pattern TIMEOUT_SEC_PATTERN =
            Pattern.compile("after\\s+(\\d+)\\s*second", Pattern.CASE_INSENSITIVE);

    /**
     * Lettuce 5.x: "Request queue size exceeded: 64".
     */
    private static final Pattern QUEUE_SIZE_PATTERN =
            Pattern.compile("(?:size|capacity)\\s+exceeded\\s*:?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * Jedis ACL: "NOPERM this user has no permissions to run the 'set' command".
     */
    private static final Pattern ACL_COMMAND_PATTERN =
            Pattern.compile("'(\\w+)'\\s+command", Pattern.CASE_INSENSITIVE);

    private static final ClassLoader LOADER = RedisExceptionStrategy.class.getClassLoader();

    // Lettuce
    @Nullable
    private static final Class<?> LETTUCE_BASE =
            ReflectionUtils.loadOptionalClass("io.lettuce.core.RedisException", LOADER);

    @Nullable
    private static final Class<?> LETTUCE_CONNECTION =
            ReflectionUtils.loadOptionalClass("io.lettuce.core.RedisConnectionException", LOADER);

    @Nullable
    private static final Class<?> LETTUCE_TIMEOUT =
            ReflectionUtils.loadOptionalClass("io.lettuce.core.RedisCommandTimeoutException", LOADER);

    @Nullable
    private static final Class<?> LETTUCE_CLUSTER =
            ReflectionUtils.loadOptionalClass("io.lettuce.core.cluster.RedisClusterException", LOADER);

    @Nullable
    private static final Class<?> LETTUCE_COMMAND_EXEC =
            ReflectionUtils.loadOptionalClass("io.lettuce.core.RedisCommandExecutionException", LOADER);

    // Jedis
    @Nullable
    private static final Class<?> JEDIS_BASE =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_CONNECTION =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisConnectionException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_DATA =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisDataException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_ACL =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisAccessControlException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_CLUSTER =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisClusterException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_NO_REACHABLE =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisNoReachableClusterNodeException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_EXHAUSTED_POOL =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisExhaustedPoolException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_BUSY =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisBusyException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_NO_SCRIPT =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisNoScriptException", LOADER);

    @Nullable
    private static final Class<?> JEDIS_VALIDATION =
            ReflectionUtils.loadOptionalClass("redis.clients.jedis.exceptions.JedisValidationException", LOADER);

    // Spring Data Redis (wraps Lettuce/Jedis)
    @Nullable
    private static final Class<?> SPRING_CONNECTION_FAILURE =
            ReflectionUtils.loadOptionalClass("org.springframework.data.redis.RedisConnectionFailureException", LOADER);

    @Nullable
    private static final Class<?> SPRING_SYSTEM =
            ReflectionUtils.loadOptionalClass("org.springframework.data.redis.RedisSystemException", LOADER);

    public RedisExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.REDIS, stackHintExtractor);
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return isRedisException(cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        RedisDriverInfo info = classify(cause);
        RedisErrorType type = info.type();

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail detail = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("redis")
                .diagnosticMetadata(buildDiagnosticMetadata(info, cause))
                .build();
        details.add(detail);

        return type.toResolution(details);
    }

    @NonNull
    private static RedisDriverInfo classify(@NonNull Throwable throwable) {
        // Spring Data Redis (проверять первым — wraps driver exceptions)
        if (ClassUtils.isInstanceOf(SPRING_CONNECTION_FAILURE, throwable)) {
            return new RedisDriverInfo("SPRING_DATA_REDIS", RedisErrorType.CONNECTION);
        }

        if (ClassUtils.isInstanceOf(SPRING_SYSTEM, throwable)) {
            Throwable nested = throwable.getCause();
            if (nested != null && isRedisException(nested)) {
                return classify(nested);
            }

            return new RedisDriverInfo("SPRING_DATA_REDIS", RedisErrorType.GENERIC);
        }

        // Lettuce (специфичные → общие; порядок важен из-за наследования)
        if (ClassUtils.isInstanceOf(LETTUCE_TIMEOUT, throwable)) {
            return new RedisDriverInfo("LETTUCE", RedisErrorType.TIMEOUT);
        }

        if (ClassUtils.isInstanceOf(LETTUCE_CONNECTION, throwable)) {
            return new RedisDriverInfo("LETTUCE", RedisErrorType.CONNECTION);
        }

        if (ClassUtils.isInstanceOf(LETTUCE_CLUSTER, throwable)) {
            return new RedisDriverInfo("LETTUCE", RedisErrorType.CLUSTER);
        }

        if (ClassUtils.isInstanceOf(LETTUCE_COMMAND_EXEC, throwable)) {
            return new RedisDriverInfo("LETTUCE", RedisErrorType.DATA);
        }

        if (ClassUtils.isInstanceOf(LETTUCE_BASE, throwable)) {
            // Lettuce queue overflow: "Request queue size exceeded" (5.x) / "Request queue capacity exceeded" (6.x)
            String message = throwable.getMessage();
            if (message != null && message.contains("queue")
                    && (message.contains("exceeded") || message.contains("capacity"))) {
                return new RedisDriverInfo("LETTUCE", RedisErrorType.OVERLOADED);
            }

            return new RedisDriverInfo("LETTUCE", RedisErrorType.GENERIC);
        }

        // Jedis (специфичные → общие; порядок: connection → overloaded → ACL → data → cluster → base)
        if (ClassUtils.isInstanceOf(JEDIS_NO_REACHABLE, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.CONNECTION);
        }

        if (ClassUtils.isInstanceOf(JEDIS_CONNECTION, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.CONNECTION);
        }

        if (ClassUtils.isInstanceOf(JEDIS_EXHAUSTED_POOL, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.OVERLOADED);
        }

        if (ClassUtils.isInstanceOf(JEDIS_BUSY, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.OVERLOADED);
        }

        if (ClassUtils.isInstanceOf(JEDIS_ACL, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.ACCESS_DENIED);
        }

        if (ClassUtils.isInstanceOf(JEDIS_NO_SCRIPT, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.DATA);
        }

        if (ClassUtils.isInstanceOf(JEDIS_VALIDATION, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.DATA);
        }

        if (ClassUtils.isInstanceOf(JEDIS_DATA, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.DATA);
        }

        if (ClassUtils.isInstanceOf(JEDIS_CLUSTER, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.CLUSTER);
        }

        if (ClassUtils.isInstanceOf(JEDIS_BASE, throwable)) {
            return new RedisDriverInfo("JEDIS", RedisErrorType.GENERIC);
        }

        return new RedisDriverInfo(null, RedisErrorType.GENERIC);
    }

    private static boolean isRedisException(@NonNull Throwable throwable) {
        return ClassUtils.isInstanceOf(LETTUCE_BASE, throwable)
                || ClassUtils.isInstanceOf(JEDIS_BASE, throwable)
                || ClassUtils.isInstanceOf(SPRING_CONNECTION_FAILURE, throwable)
                || ClassUtils.isInstanceOf(SPRING_SYSTEM, throwable);
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull RedisDriverInfo info,
                                                        @NonNull Throwable cause) {
        String message = cause.getMessage();

        DiagnosticMetadataBuilder builder = diagnosticMetadataFor(cause)
                .put("cacheProvider", "REDIS")
                .putIfPresent("redisDriver", info.driver())
                .putTruncated("causeMessage", message, MAX_CAUSE_MESSAGE_LENGTH);

        switch (info.type()) {
            case TIMEOUT -> extractTimeoutMetadata(message, builder);
            case CLUSTER -> extractClusterMetadata(message, builder);
            case DATA -> extractDataErrorMetadata(message, builder);
            case ACCESS_DENIED -> extractAclMetadata(message, builder);
            case OVERLOADED -> extractOverloadedMetadata(message, builder);
            default -> {
            }
        }

        return builder
                .withRootCause(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH, MAX_CAUSE_MESSAGE_LENGTH)
                .withStackHint()
                .build();
    }

    private static void extractTimeoutMetadata(@Nullable String message,
                                               @NonNull DiagnosticMetadataBuilder builder) {
        if (message == null) {
            return;
        }

        Matcher ms = TIMEOUT_MS_PATTERN.matcher(message);
        if (ms.find()) {
            builder.put("timeoutMs", ms.group(1));
            return;
        }

        Matcher sec = TIMEOUT_SEC_PATTERN.matcher(message);
        if (sec.find()) {
            long millis = Long.parseLong(sec.group(1)) * 1000;
            builder.put("timeoutMs", String.valueOf(millis));
        }
    }

    private static void extractClusterMetadata(@Nullable String message,
                                               @NonNull DiagnosticMetadataBuilder builder) {
        if (message == null) {
            return;
        }

        String clusterError = null;
        if (message.contains("CLUSTERDOWN")) {
            clusterError = "CLUSTERDOWN";
        } else if (message.contains("MOVED")) {
            clusterError = "MOVED";
        } else if (message.contains("ASK ")) {
            clusterError = "ASK";
        } else if (message.toLowerCase().contains("no reachable")) {
            clusterError = "NO_REACHABLE_NODE";
        } else if (message.toLowerCase().contains("redirect")) {
            clusterError = "TOO_MANY_REDIRECTIONS";
        }

        if (clusterError != null) {
            builder.put("clusterErrorType", clusterError);
        }
    }

    private static void extractDataErrorMetadata(@Nullable String message,
                                                 @NonNull DiagnosticMetadataBuilder builder) {
        if (message == null) {
            return;
        }

        String prefix = extractRedisErrorPrefix(message);
        if (prefix != null) {
            builder.put("redisErrorPrefix", prefix);
        }
    }

    @Nullable
    private static String extractRedisErrorPrefix(@NonNull String message) {
        int space = message.indexOf(' ');
        if (space > 0 && space <= 20) {
            String prefix = message.substring(0, space);
            if (REDIS_PREFIX_PATTERN.matcher(prefix).matches()) {
                return prefix;
            }
        }

        return null;
    }

    private static void extractAclMetadata(@Nullable String message,
                                           @NonNull DiagnosticMetadataBuilder builder) {
        if (message == null) {
            return;
        }

        if (message.contains("NOPERM")) {
            builder.put("aclErrorType", "NOPERM");
        } else if (message.contains("NOAUTH")) {
            builder.put("aclErrorType", "NOAUTH");
        } else if (message.contains("WRONGPASS")) {
            builder.put("aclErrorType", "WRONGPASS");
        }

        Matcher cmd = ACL_COMMAND_PATTERN.matcher(message);
        if (cmd.find()) {
            builder.put("deniedCommand", cmd.group(1));
        }
    }

    private static void extractOverloadedMetadata(@Nullable String message,
                                                  @NonNull DiagnosticMetadataBuilder builder) {
        if (message == null) {
            return;
        }

        if (message.toLowerCase().contains("queue")) {
            builder.put("overloadedReason", "QUEUE_FULL");

            Matcher qm = QUEUE_SIZE_PATTERN.matcher(message);
            if (qm.find()) {
                builder.put("queueSize", qm.group(1));
            }
        } else if (message.contains("BUSY")) {
            builder.put("overloadedReason", "SERVER_BUSY");
        } else if (message.toLowerCase().contains("pool")) {
            builder.put("overloadedReason", "POOL_EXHAUSTED");
        }
    }

    /**
     * Семантическая классификация ошибок Redis независимо от конкретного драйвера.
     *
     * <p>Поле {@link #message()} содержит только безопасные формулировки
     * без упоминания внутренней топологии или данных (CWE-209).
     */
    private enum RedisErrorType implements ErrorClassification {
        TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "The Redis operation did not complete within the configured timeout."),

        CONNECTION(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "Unable to establish a connection to the Redis server."),

        CLUSTER(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The Redis cluster is temporarily unavailable or reconfiguring."),

        DATA(
                HttpStatus.BAD_GATEWAY,
                Retryability.NOT_RETRYABLE,
                "The Redis server returned an unexpected or invalid response."),

        ACCESS_DENIED(
                HttpStatus.FORBIDDEN,
                Retryability.NOT_RETRYABLE,
                "Access was denied due to insufficient Redis ACL permissions."),

        OVERLOADED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The Redis connection pool is exhausted. The service is temporarily overloaded."),

        GENERIC(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The Redis cache service is temporarily unavailable.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        RedisErrorType(@NonNull HttpStatus httpStatus,
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

    private record RedisDriverInfo(@Nullable String driver, @NonNull RedisErrorType type) {
    }
}
