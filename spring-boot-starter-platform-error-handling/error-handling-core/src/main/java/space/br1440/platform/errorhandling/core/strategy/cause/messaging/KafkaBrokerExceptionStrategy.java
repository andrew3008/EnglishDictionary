package space.br1440.platform.errorhandling.core.strategy.cause.messaging;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.CauseMetadataEnricher;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Стратегия классификации Kafka broker-уровневых исключений как первопричины бизнес-исключений.
 *
 * <h2>Scope</h2>
 * <p>Покрывает <b>broker connectivity / auth / transactional / generic</b> ошибки
 * Spring Kafka и нативного {@code kafka-clients}. Serialization/schema-ошибки
 * {@code org.apache.kafka.common.errors.SerializationException} и подобные
 * обрабатываются отдельно в {@link KafkaSerializationExceptionStrategy}
 * (order=430), который вызывается раньше данной стратегии (order=435).
 *
 * <h2>Поддерживаемые исключения</h2>
 *
 * <h3>Spring Kafka обёртки</h3>
 * <ul>
 *   <li>{@code org.springframework.kafka.KafkaException}</li>
 *   <li>{@code org.springframework.kafka.listener.ListenerExecutionFailedException}</li>
 *   <li>{@code org.springframework.kafka.requestreply.KafkaReplyTimeoutException}</li>
 *   <li>{@code org.springframework.kafka.core.KafkaProducerException}</li>
 * </ul>
 *
 * <h3>Native kafka-clients (без Spring-обёртки)</h3>
 * <p>Обнаруживаются type-safe через {@code instanceof} reflection-loaded классов:
 * <ul>
 *   <li>{@code org.apache.kafka.common.errors.AuthenticationException}</li>
 *   <li>{@code org.apache.kafka.common.errors.AuthorizationException}</li>
 *   <li>{@code org.apache.kafka.common.errors.TimeoutException}</li>
 *   <li>{@code org.apache.kafka.common.errors.ProducerFencedException}</li>
 *   <li>{@code org.apache.kafka.common.errors.OutOfOrderSequenceException}</li>
 * </ul>
 *
 * <p>Native проверки требуются: при прямом использовании {@code KafkaProducer.send().get()}
 * исключение поднимается без Spring-обёртки и попадает в cause chain как
 * {@code ExecutionException → KafkaException}, либо сразу как нативный type.
 *
 * <h2>Классификация (приоритет сверху вниз)</h2>
 * <pre>
 *   1. {@code AUTH_FAILURE}            → {@code AuthenticationException}                     500 NOT_RETRYABLE
 *   2. {@code AUTHORIZATION_DENIED}    → {@code AuthorizationException}                      403 NOT_RETRYABLE
 *   3. {@code TRANSACTION_FATAL}       → {@code ProducerFenced}/{@code OutOfOrderSequence}   500 NOT_RETRYABLE
 *   4. {@code TIMEOUT}                 → {@code TimeoutException}/{@code KafkaReplyTimeout}  504 RETRYABLE
 *   5. {@code CONNECTION_FAILURE}      → {@code ConnectException}/{@code UnknownHostException} в cause-chain  503 RETRYABLE
 *   6. {@code GENERIC}                 → fallback                                            503 NOT_RETRYABLE
 * </pre>
 *
 * <h2>Безопасность (CWE-209/312)</h2>
 *
 * <h3>{@code diagnosticMetadata} (SRE-only, не попадает в DTO)</h3>
 * <ul>
 *   <li>Безусловные: {@code causeMessage} (truncated 200), {@code stackHint},
 *       {@code rootCauseClass}/{@code rootCauseMessage}. Идентификация типа покрыта
 *       полями {@code domain=kafka} (из {@code reason}) и {@code rootCauseClass} —
 *       отдельные {@code brokerType}/{@code exceptionClass} избыточны и удалены.</li>
 *   <li>Условные: {@code networkCauseClass}/{@code networkCauseMessage} для {@code CONNECTION_FAILURE}/{@code TIMEOUT}
 *       при наличии сетевого cause; {@code authMechanism} для {@code AUTH_FAILURE} (regex по whitelist
 *       {@code SASL/SCRAM/PLAIN/GSSAPI/...}); {@code transactionalId} для {@code TRANSACTION_FATAL}
 *       (regex с length cap 128 через {@link LogSanitizer}).</li>
 *   <li>Координаты ({@code topic}, {@code partition}, {@code offset}, {@code consumerGroup},
 *       {@code deliveryAttempt}, {@code kafkaOperation}) — только при {@code includeBrokerCoordinates=true};
 *       читаются из {@code getFailedMessage().getHeaders()} через
 *       {@link MessagingMetadataUtils#enrichBuilderWithMessageHeaders} с whitelist-фильтром
 *       по константам {@code org.springframework.kafka.support.KafkaHeaders}.</li>
 *   <li>{@code kafkaErrorClass} — SimpleName первого нативного исключения
 *       {@code org.apache.kafka.common.errors.*} в cause chain (диагностический хинт SRE).</li>
 * </ul>
 */
public class KafkaBrokerExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 200;

    /**
     * Лимит длины {@code transactional.id}, извлечённого регулярным выражением из
     * текста {@code ProducerFencedException}/{@code OutOfOrderSequenceException}.
     * Kafka допускает {@code transactional.id} до 249 символов, и значение конфигурируется
     * приложением (может содержать tenant prefix). Cap защищает {@code diagnosticMetadata}
     * от непредвиденных размеров (CWE-209/CWE-400).
     */
    private static final int MAX_TRANSACTIONAL_ID_LENGTH = 128;

    private static final ClassLoader LOADER = KafkaBrokerExceptionStrategy.class.getClassLoader();

    /** Базовый Spring Kafka type. */
    @Nullable
    private static final Class<?> KAFKA_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.kafka.KafkaException", LOADER);

    @Nullable
    private static final Class<?> KAFKA_LISTENER_FAILED =
            ReflectionUtils.loadOptionalClass("org.springframework.kafka.listener.ListenerExecutionFailedException", LOADER);

    @Nullable
    private static final Class<?> KAFKA_REPLY_TIMEOUT =
            ReflectionUtils.loadOptionalClass("org.springframework.kafka.requestreply.KafkaReplyTimeoutException", LOADER);

    @Nullable
    private static final Class<?> KAFKA_PRODUCER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.kafka.core.KafkaProducerException", LOADER);

    /** Native kafka-clients types — могут попасть в cause chain без Spring-обёртки. */
    @Nullable
    private static final Class<?> NATIVE_AUTH =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.AuthenticationException", LOADER);

    @Nullable
    private static final Class<?> NATIVE_AUTHZ =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.AuthorizationException", LOADER);

    @Nullable
    private static final Class<?> NATIVE_TIMEOUT =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.TimeoutException", LOADER);

    @Nullable
    private static final Class<?> NATIVE_PRODUCER_FENCED =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.ProducerFencedException", LOADER);

    @Nullable
    private static final Class<?> NATIVE_OUT_OF_ORDER_SEQUENCE =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.OutOfOrderSequenceException", LOADER);

    private final boolean includeBrokerCoordinates;

    public KafkaBrokerExceptionStrategy(boolean includeBrokerCoordinates,
                                        @NonNull StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.KAFKA_BROKER, stackHintExtractor);
        this.includeBrokerCoordinates = includeBrokerCoordinates;
    }

    /**
     * Принимает как Spring-обёртку {@code KafkaException}, так и native kafka-clients
     * исключения, которые могут оказаться в cause chain без Spring-уровня
     * (прямой {@code KafkaProducer.send().get()}).
     */
    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(KAFKA_EXCEPTION, cause)
                || isNativeKafkaException(cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        KafkaBrokerErrorType errorType = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (errorType.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(errorType.name())
                .domain("kafka")
                .diagnosticMetadata(buildDiagnosticMetadata(errorType, cause))
                .build();
        details.add(info);

        return errorType.toResolution(details);
    }

    /**
     * Broker-уровневые ошибки Kafka — серверный сбой инфраструктуры (5xx).
     * 4xx ({@code AUTHORIZATION_DENIED} = 403) ниже категорий 5xx по серьёзности
     * для логирования: Spring listener container будет независимо ретраить
     * подключение, не нужно засорять ERROR-уровень.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry errorEntry) {
        HttpStatus httpStatus = errorEntry.getHttpStatus();
        if (httpStatus.is5xxServerError()) {
            return LogPolicy.ERROR;
        }

        if (httpStatus.is4xxClientError()) {
            return LogPolicy.WARN;
        }

        return LogPolicy.DEBUG;
    }

    @NonNull
    private static KafkaBrokerErrorType classify(@NonNull Throwable exception) {
        if (isInstanceOfChain(exception, NATIVE_AUTH)) {
            return KafkaBrokerErrorType.AUTH_FAILURE;
        }

        if (isInstanceOfChain(exception, NATIVE_AUTHZ)) {
            return KafkaBrokerErrorType.AUTHORIZATION_DENIED;
        }

        if (isInstanceOfChain(exception, NATIVE_PRODUCER_FENCED, NATIVE_OUT_OF_ORDER_SEQUENCE)) {
            return KafkaBrokerErrorType.TRANSACTION_FATAL;
        }

        if (ClassUtils.isInstanceOf(KAFKA_REPLY_TIMEOUT, exception)
                || isInstanceOfChain(exception, NATIVE_TIMEOUT)) {
            return KafkaBrokerErrorType.TIMEOUT;
        }

        Throwable network = MessagingMetadataUtils.unwrapNetworkCause(exception, CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH);
        if (network != exception) {
            if (network instanceof SocketTimeoutException) {
                return KafkaBrokerErrorType.TIMEOUT;
            }

            return KafkaBrokerErrorType.CONNECTION_FAILURE;
        }

        return KafkaBrokerErrorType.GENERIC;
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull KafkaBrokerErrorType errorType,
                                                        @NonNull Throwable cause) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor);

        String causeMessage = cause.getMessage();
        builder.putTruncatedIfPresent("causeMessage", causeMessage, MAX_CAUSE_MESSAGE_LENGTH);

        builder.withRootCause(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH, MAX_CAUSE_MESSAGE_LENGTH);

        if (errorType == KafkaBrokerErrorType.CONNECTION_FAILURE || errorType == KafkaBrokerErrorType.TIMEOUT) {
            Throwable network = MessagingMetadataUtils.unwrapNetworkCause(cause, CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH);
            if (network != cause) {
                builder.put("networkCauseClass", network.getClass().getName());
                builder.putTruncatedIfPresent("networkCauseMessage", network.getMessage(), MAX_CAUSE_MESSAGE_LENGTH);
            }
        }

        if (errorType == KafkaBrokerErrorType.AUTH_FAILURE) {
            String mech = MessagingMetadataUtils.extractAuthMechanism(causeMessage);
            if (mech != null) {
                builder.put("authMechanism", mech);
            }
        }

        if (errorType == KafkaBrokerErrorType.TRANSACTION_FATAL) {
            String txId = MessagingMetadataUtils.extractTransactionalId(causeMessage);
            if (txId != null) {
                builder.put("transactionalId", LogSanitizer.sanitize(txId, MAX_TRANSACTIONAL_ID_LENGTH));
            }
        }

        if (includeBrokerCoordinates) {
            MessagingMetadataUtils.enrichBuilderWithMessageHeaders(
                    cause,
                    MessagingMetadataUtils.KAFKA_HEADER_TO_LOGICAL_KEY,
                    builder
            );

            enrichWithKafkaProducerRecord(cause, builder);
            enrichWithListenerGroupId(cause, builder);

            String operation = detectOperation(cause);
            if (operation != null) {
                builder.put("kafkaOperation", operation);
            }
        }

        enrichWithNativeKafkaErrorClass(cause, builder);

        return builder.withStackHint().build();
    }

    private static void enrichWithKafkaProducerRecord(@NonNull Throwable cause,
                                                      @NonNull DiagnosticMetadataBuilder builder) {
        if (!ClassUtils.isInstanceOf(KAFKA_PRODUCER_EXCEPTION, cause)) {
            return;
        }

        ReflectionUtils.invokeMethod(cause, "getFailedProducerRecord", record -> {
            if (record == null) {
                return;
            }

            ReflectionUtils.invokeMethod(record, "topic", topic -> {
                if (topic instanceof String t && StringUtils.hasText(t)) {
                    builder.putIfPresent("topic", t);
                }
            });
        });
    }

    private static void enrichWithListenerGroupId(@NonNull Throwable cause,
                                                  @NonNull DiagnosticMetadataBuilder builder) {
        if (!ClassUtils.isInstanceOf(KAFKA_LISTENER_FAILED, cause)) {
            return;
        }

        ReflectionUtils.invokeMethod(cause, "getGroupId", groupId -> {
            if (groupId instanceof String s && StringUtils.hasText(s)) {
                builder.putIfPresent("consumerGroup", s);
            }
        });
    }

    private static void enrichWithNativeKafkaErrorClass(@NonNull Throwable cause,
                                                        @NonNull DiagnosticMetadataBuilder builder) {
        Throwable current = cause;
        for (int depth = 0; depth < CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH; depth++) {
            String className = current.getClass().getName();
            if (className.startsWith("org.apache.kafka.common.errors.")) {
                builder.put("kafkaErrorClass", current.getClass().getSimpleName());
                return;
            }

            Throwable next = current.getCause();
            if (next == null || next == current) {
                return;
            }

            current = next;
        }
    }

    @Nullable
    private static String detectOperation(@NonNull Throwable exception) {
        if (ClassUtils.isInstanceOf(KAFKA_LISTENER_FAILED, exception)) {
            return "RECEIVE";
        }

        if (ClassUtils.isInstanceOf(KAFKA_PRODUCER_EXCEPTION, exception)
                || ClassUtils.isInstanceOf(KAFKA_REPLY_TIMEOUT, exception)) {
            return "SEND";
        }

        return null;
    }

    private static boolean isInstanceOfChain(@NonNull Throwable throwable,
                                             @Nullable Class<?>... classes) {
        if (classes == null) {
            return false;
        }

        Throwable current = throwable;
        for (int depth = 0; depth < CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH; depth++) {
            for (Class<?> clazz : classes) {
                if (ClassUtils.isInstanceOf(clazz, current)) {
                    return true;
                }
            }

            Throwable next = current.getCause();
            if (next == null || next == current) {
                return false;
            }

            current = next;
        }

        return false;
    }

    private static boolean isNativeKafkaException(@NonNull Throwable throwable) {
        return MessagingMetadataUtils.isInstanceOfAny(
                throwable,
                NATIVE_AUTH, NATIVE_AUTHZ, NATIVE_TIMEOUT,
                NATIVE_PRODUCER_FENCED, NATIVE_OUT_OF_ORDER_SEQUENCE
        );
    }

    private enum KafkaBrokerErrorType implements ErrorClassification {

        AUTH_FAILURE(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Kafka authentication failed. The configured credentials are invalid or the auth provider is unreachable."),

        AUTHORIZATION_DENIED(
                HttpStatus.FORBIDDEN,
                Retryability.NOT_RETRYABLE,
                "The Kafka client is not authorized to perform this operation."),

        TRANSACTION_FATAL(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Kafka producer transaction is in a fatal state and cannot be recovered. The producer instance must be recreated."),

        TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "The Kafka broker did not respond within the configured timeout."),

        CONNECTION_FAILURE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "Unable to connect to the Kafka broker."),

        GENERIC(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "An unclassified error occurred during Kafka broker communication.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        KafkaBrokerErrorType(@NonNull HttpStatus httpStatus,
                             @NonNull Retryability retryability,
                             @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @Override @NonNull public HttpStatus httpStatus() {
            return httpStatus;
        }

        @Override @NonNull public Retryability retryability() {
            return retryability;
        }

        @Override @NonNull public String message() {
            return message;
        }
    }
}
