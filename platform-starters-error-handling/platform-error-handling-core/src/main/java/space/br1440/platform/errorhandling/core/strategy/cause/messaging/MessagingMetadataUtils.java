package space.br1440.platform.errorhandling.core.strategy.cause.messaging;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Общая утилита для трёх messaging-стратегий ({@link KafkaBrokerExceptionStrategy},
 * {@link RabbitMqExceptionStrategy}, {@link SpringMessagingExceptionStrategy}).
 *
 * <h2>Назначение</h2>
 * <ul>
 *   <li>Сетевые и type-safe проверки cause chain ({@link #unwrapNetworkCause},
 *       {@link #isNetworkFailure}, {@link #isInstanceOfAny}).</li>
 *   <li>Регулярные экстракторы текстовых деталей ({@link #extractAuthMechanism},
 *       {@link #extractTransactionalId}) — последний резорт после type-safe
 *       классификации.</li>
 *   <li>Безопасный whitelist системных заголовков для каждого транспорта
 *       ({@link #KAFKA_SAFE_HEADERS}, {@link #RABBIT_SAFE_HEADERS},
 *       {@link #SPRING_MSG_SAFE_HEADERS}) и единая точка извлечения headers
 *       из {@code getFailedMessage()} ({@link #enrichWithMessageHeaders}).</li>
 * </ul>
 *
 * <h2>Безопасность (CWE-209)</h2>
 * <p>Application-defined headers (correlation tokens, tenant IDs, JWT claims,
 * user context) <b>не извлекаются</b> ни при каких условиях. В callback попадают
 * только заголовки, чьи имена явно перечислены в whitelist-наборе.
 */
@UtilityClass
public final class MessagingMetadataUtils {

     private static final ClassLoader LOADER = MessagingMetadataUtils.class.getClassLoader();

     private static final Pattern TRANSACTIONAL_ID_PATTERN =
            Pattern.compile("transactional\\.id[=:\\s]+([\\w.\\-]+)");

    private static final Pattern AUTH_MECHANISM_PATTERN =
            Pattern.compile("\\b(SASL|SCRAM|PLAIN|GSSAPI|OAUTHBEARER|SSL|CERTIFICATE|EXTERNAL|ANONYMOUS)\\b",
                    Pattern.CASE_INSENSITIVE);

    public static final Set<String> KAFKA_SAFE_HEADERS = resolveKafkaSafeHeaders();

    public static final Set<String> RABBIT_SAFE_HEADERS = resolveRabbitSafeHeaders();

    public static final Set<String> SPRING_MSG_SAFE_HEADERS = Set.of("id", "timestamp", "contentType");

    public static final Map<String, String> KAFKA_HEADER_TO_LOGICAL_KEY = buildKafkaLogicalKeys();

    public static final Map<String, String> RABBIT_HEADER_TO_LOGICAL_KEY = buildRabbitLogicalKeys();

    @FunctionalInterface
    public interface HeaderConsumer {
        void accept(@NonNull String logicalKey, @NonNull String value);
    }

    @NonNull
    public static Throwable unwrapNetworkCause(@NonNull Throwable exception, int maxDepth) {
        Throwable current = exception;
        for (int i = 0; i < maxDepth; i++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }

            if (isNetworkFailure(cause)) {
                return cause;
            }

            current = cause;
        }

        return exception;
    }

    public static boolean isNetworkFailure(@Nullable Throwable cause) {
        return (cause instanceof ConnectException)
                || (cause instanceof UnknownHostException)
                || (cause instanceof SocketTimeoutException);
    }

    public static boolean isInstanceOfAny(@NonNull Object target,
                                          @Nullable Class<?>... classes) {
        if (classes == null) {
            return false;
        }

        for (Class<?> clazz : classes) {
            if (ClassUtils.isInstanceOf(clazz, target)) {
                return true;
            }
        }

        return false;
    }

    @Nullable
    public static String extractAuthMechanism(@Nullable String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }

        Matcher matcher = AUTH_MECHANISM_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1).toUpperCase() : null;
    }

    @Nullable
    public static String extractTransactionalId(@Nullable String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }

        Matcher matcher = TRANSACTIONAL_ID_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static void enrichWithMessageHeaders(@NonNull Throwable exception,
                                                @NonNull Map<String, String> rawToLogicalKey,
                                                @NonNull HeaderConsumer consumer) {
        ReflectionUtils.invokeMethod(exception, "getFailedMessage", failedMessage -> {
            if (failedMessage == null) {
                return;
            }

            ReflectionUtils.invokeMethod(failedMessage, "getHeaders", headersObj -> {
                if (!(headersObj instanceof Map<?, ?> headers)) {
                    return;
                }

                for (Map.Entry<String, String> entry : rawToLogicalKey.entrySet()) {
                    Object rawValue = headers.get(entry.getKey());
                    String stringValue = stringifyHeader(rawValue);
                    if (StringUtils.hasText(stringValue)) {
                        consumer.accept(entry.getValue(), stringValue);
                    }
                }
            });
        });
    }

    public static void enrichBuilderWithMessageHeaders(@NonNull Throwable exception,
                                                       @NonNull Map<String, String> rawToLogicalKey,
                                                       @NonNull DiagnosticMetadataBuilder builder) {
        enrichWithMessageHeaders(exception, rawToLogicalKey, builder::put);
    }

    @Nullable
    private static String stringifyHeader(@Nullable Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String s) {
            return s;
        }

        return String.valueOf(value);
    }

    @NonNull
    private static Set<String> resolveKafkaSafeHeaders() {
        Set<String> set = new LinkedHashSet<>();
        addOrFallback(set, "org.springframework.kafka.support.KafkaHeaders", "RECEIVED_TOPIC", "kafka_receivedTopic");
        addOrFallback(set, "org.springframework.kafka.support.KafkaHeaders", "RECEIVED_PARTITION", "kafka_receivedPartitionId");
        addOrFallback(set, "org.springframework.kafka.support.KafkaHeaders", "OFFSET", "kafka_offset");
        addOrFallback(set, "org.springframework.kafka.support.KafkaHeaders", "GROUP_ID", "kafka_groupId");
        addOrFallback(set, "org.springframework.kafka.support.KafkaHeaders", "DELIVERY_ATTEMPT", "kafka_deliveryAttempt");
        addOrFallback(set, "org.springframework.kafka.support.KafkaHeaders", "RECEIVED_TIMESTAMP", "kafka_receivedTimestamp");
        addOrFallback(set, "org.springframework.kafka.support.KafkaHeaders", "TIMESTAMP_TYPE", "kafka_timestampType");
        addOrFallback(set, "org.springframework.kafka.support.KafkaHeaders", "CORRELATION_ID", "kafka_correlationId");
        return Collections.unmodifiableSet(set);
    }

    @NonNull
    private static Set<String> resolveRabbitSafeHeaders() {
        Set<String> set = new LinkedHashSet<>();
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "DELIVERY_TAG", "amqp_deliveryTag");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "REDELIVERED", "amqp_redelivered");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "RECEIVED_EXCHANGE", "amqp_receivedExchange");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "RECEIVED_ROUTING_KEY", "amqp_receivedRoutingKey");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "CONSUMER_QUEUE", "amqp_consumerQueue");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "MESSAGE_ID", "amqp_messageId");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "CORRELATION_ID", "amqp_correlationId");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "CONTENT_TYPE", "amqp_contentType");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "CONTENT_ENCODING", "amqp_contentEncoding");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "TIMESTAMP", "amqp_timestamp");
        addOrFallback(set, "org.springframework.amqp.support.AmqpHeaders", "DELIVERY_MODE", "amqp_deliveryMode");
        return Collections.unmodifiableSet(set);
    }

    @NonNull
    private static Map<String, String> buildKafkaLogicalKeys() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        putKafkaLogical(map, "RECEIVED_TOPIC", "kafka_receivedTopic", "topic");
        putKafkaLogical(map, "RECEIVED_PARTITION", "kafka_receivedPartitionId", "partition");
        putKafkaLogical(map, "OFFSET", "kafka_offset", "offset");
        putKafkaLogical(map, "GROUP_ID", "kafka_groupId", "consumerGroup");
        putKafkaLogical(map, "DELIVERY_ATTEMPT", "kafka_deliveryAttempt", "deliveryAttempt");
        return Collections.unmodifiableMap(map);
    }

    @NonNull
    private static Map<String, String> buildRabbitLogicalKeys() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        putRabbitLogical(map, "CONSUMER_QUEUE", "amqp_consumerQueue", "queue");
        putRabbitLogical(map, "RECEIVED_ROUTING_KEY", "amqp_receivedRoutingKey", "routingKey");
        putRabbitLogical(map, "RECEIVED_EXCHANGE", "amqp_receivedExchange", "exchange");
        putRabbitLogical(map, "DELIVERY_TAG", "amqp_deliveryTag", "deliveryTag");
        putRabbitLogical(map, "REDELIVERED", "amqp_redelivered", "redelivered");
        return Collections.unmodifiableMap(map);
    }

    private static void putKafkaLogical(@NonNull Map<String, String> sink,
                                        @NonNull String constantName,
                                        @NonNull String fallback,
                                        @NonNull String logicalKey) {
        String raw = resolveConstant("org.springframework.kafka.support.KafkaHeaders", constantName, fallback);
        sink.put(raw, logicalKey);
    }

    private static void putRabbitLogical(@NonNull Map<String, String> sink,
                                         @NonNull String constantName,
                                         @NonNull String fallback,
                                         @NonNull String logicalKey) {
        String raw = resolveConstant("org.springframework.amqp.support.AmqpHeaders", constantName, fallback);
        sink.put(raw, logicalKey);
    }

    @NonNull
    private static String resolveConstant(@NonNull String className,
                                          @NonNull String fieldName,
                                          @NonNull String fallback) {
        try {
            Class<?> cls = Class.forName(className, false, LOADER);
            Field field = cls.getField(fieldName);

            Object value = field.get(null);
            return (value instanceof String s) ? s : fallback;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return fallback;
        }
    }

    private static void addOrFallback(@NonNull Set<String> sink,
                                      @NonNull String className,
                                      @NonNull String fieldName,
                                      @NonNull String fallback) {
        sink.add(resolveConstant(className, fieldName, fallback));
    }
}
