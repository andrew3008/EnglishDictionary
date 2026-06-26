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
 * Стратегия классификации RabbitMQ / AMQP broker-уровневых исключений как первопричины бизнес-исключений.
 *
 * <h2>Поддерживаемые исключения</h2>
 *
 * <h3>Spring AMQP типы</h3>
 * <ul>
 *   <li>{@code org.springframework.amqp.AmqpException} — root всех Spring AMQP исключений</li>
 *   <li>{@code org.springframework.amqp.AmqpConnectException} — невозможно подключиться</li>
 *   <li>{@code org.springframework.amqp.AmqpTimeoutException} — таймаут операции</li>
 *   <li>{@code org.springframework.amqp.AmqpAuthenticationException} — auth-сбой</li>
 *   <li>{@code org.springframework.amqp.AmqpResourceNotAvailableException} — ресурс недоступен</li>
 *   <li>{@code org.springframework.amqp.AmqpRejectAndDontRequeueException} — DLQ-сценарий</li>
 *   <li>{@code org.springframework.amqp.rabbit.listener.exception.ListenerExecutionFailedException} — обёртка listener-сбоя</li>
 * </ul>
 *
 * <h2>Безопасность (CWE-209/312)</h2>
 *
 * <h3>{@code diagnosticMetadata} (SRE-only)</h3>
 * <ul>
 *   <li>Безусловные: {@code brokerType=RABBIT}, {@code exceptionClass}, {@code causeMessage} (truncated 300),
 *       {@code stackHint}, {@code rootCauseClass}/{@code rootCauseMessage}.</li>
 *   <li>Условные: {@code networkCauseClass}/{@code networkCauseMessage} для CONNECTION/TIMEOUT;
 *       {@code amqpReplyCode}/{@code amqpReplyText} (truncated) при наличии {@code ShutdownSignalException}
 *       в cause chain.</li>
 *   <li>Координаты ({@code queue}, {@code routingKey}, {@code exchange}, {@code deliveryTag},
 *       {@code redelivered}) — только при {@code includeBrokerCoordinates=true}.</li>
 * </ul>
 *
 * <h3>Исключение из правила {@code includeBrokerCoordinates=false}</h3>
 * <p>{@code queue} для классификации {@code REJECT_NO_REQUEUE} (DLQ-сценарий)
 * <b>всегда</b> логируется в {@code diagnosticMetadata}, независимо от настройки
 * {@code includeBrokerCoordinates}. Это намеренное исключение: без имени очереди
 * SRE не может расследовать DLQ-инцидент. Контракт документирован также в
 * {@code ErrorHandlingProperties.Messaging.Rabbit.includeBrokerCoordinates}.
 */
public class RabbitMqExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;
    private static final int MAX_REPLY_TEXT_LENGTH = 200;

    private static final ClassLoader LOADER = RabbitMqExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> AMQP_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.amqp.AmqpException", LOADER);

    @Nullable
    private static final Class<?> AMQP_CONNECT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.amqp.AmqpConnectException", LOADER);

    @Nullable
    private static final Class<?> AMQP_TIMEOUT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.amqp.AmqpTimeoutException", LOADER);

    @Nullable
    private static final Class<?> AMQP_AUTH_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.amqp.AmqpAuthenticationException", LOADER);

    @Nullable
    private static final Class<?> AMQP_RESOURCE_NOT_AVAILABLE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.amqp.AmqpResourceNotAvailableException", LOADER);

    @Nullable
    private static final Class<?> AMQP_REJECT_NO_REQUEUE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.amqp.AmqpRejectAndDontRequeueException", LOADER);

    @Nullable
    private static final Class<?> SHUTDOWN_SIGNAL_EXCEPTION =
            ReflectionUtils.loadOptionalClass("com.rabbitmq.client.ShutdownSignalException", LOADER);

    private final boolean includeBrokerCoordinates;

    public RabbitMqExceptionStrategy(boolean includeBrokerCoordinates,
                                     @NonNull StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.RABBITMQ, stackHintExtractor);
        this.includeBrokerCoordinates = includeBrokerCoordinates;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(AMQP_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(SHUTDOWN_SIGNAL_EXCEPTION, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        AmqpReplyContext replyContext = AmqpReplyContext.unwrap(cause);
        RabbitErrorType errorType = classify(cause, replyContext);

        List<ErrorDetail> details = new ArrayList<>();
        if (errorType.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(errorType.name())
                .domain("rabbitmq")
                .diagnosticMetadata(buildDiagnosticMetadata(errorType, cause, replyContext))
                .build();
        details.add(info);

        return errorType.toResolution(details);
    }

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
    private static RabbitErrorType classify(@NonNull Throwable exception,
                                            @NonNull AmqpReplyContext replyContext) {
        // Уровень 1: Spring AMQP type-safe (наиболее специфично).
        if (ClassUtils.isInstanceOf(AMQP_AUTH_EXCEPTION, exception)) {
            return RabbitErrorType.AUTH_FAILURE;
        }

        if (ClassUtils.isInstanceOf(AMQP_REJECT_NO_REQUEUE_EXCEPTION, exception)) {
            return RabbitErrorType.REJECT_NO_REQUEUE;
        }

        if (ClassUtils.isInstanceOf(AMQP_RESOURCE_NOT_AVAILABLE_EXCEPTION, exception)) {
            return RabbitErrorType.RESOURCE_NOT_AVAILABLE;
        }

        if (ClassUtils.isInstanceOf(AMQP_CONNECT_EXCEPTION, exception)) {
            return RabbitErrorType.CONNECTION_FAILURE;
        }

        if (ClassUtils.isInstanceOf(AMQP_TIMEOUT_EXCEPTION, exception)) {
            return RabbitErrorType.TIMEOUT;
        }

        // Уровень 2: AMQP reply code из ShutdownSignalException (если Spring class не distinct).
        if (replyContext.replyCode() != null) {
            RabbitErrorType byReply = byReplyCode(replyContext.replyCode());
            if (byReply != null) {
                return byReply;
            }
        }

        // Уровень 3: сетевая первопричина в cause chain.
        Throwable network = MessagingMetadataUtils.unwrapNetworkCause(
                exception, CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH);
        if (network != exception) {
            if (network instanceof SocketTimeoutException) {
                return RabbitErrorType.TIMEOUT;
            }

            return RabbitErrorType.CONNECTION_FAILURE;
        }

        return RabbitErrorType.GENERIC;
    }

    /**
     * Маппит протокольный AMQP reply code на семантический тип.
     *
     * <p>Семантика кодов соответствует AMQP 0-9-1 спецификации
     * (<a href="https://www.rabbitmq.com/amqp-0-9-1-reference">rabbitmq.com/amqp-0-9-1-reference</a>).
     * Vendor-specific маппинги (например, использование 320 для version mismatch)
     * не учитываются — они выходят за пределы стандартной спецификации и должны
     * обрабатываться индивидуально приложением.
     */
    @Nullable
    private static RabbitErrorType byReplyCode(int replyCode) {
        return switch (replyCode) {
            case 320 -> RabbitErrorType.CONNECTION_FORCED;
            case 403 -> RabbitErrorType.ACCESS_REFUSED;
            case 404 -> RabbitErrorType.RESOURCE_NOT_FOUND;
            case 405 -> RabbitErrorType.RESOURCE_LOCKED;
            case 406 -> RabbitErrorType.PRECONDITION_FAILED;
            case 501, 502, 503, 505, 540 -> RabbitErrorType.PROTOCOL_VIOLATION;
            case 504 -> RabbitErrorType.CHANNEL_ERROR;
            case 506 -> RabbitErrorType.BROKER_RESOURCE_ERROR;
            case 530 -> RabbitErrorType.ACCESS_REFUSED;
            case 541 -> RabbitErrorType.BROKER_INTERNAL_ERROR;
            default -> null;
        };
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull RabbitErrorType errorType,
                                                        @NonNull Throwable cause,
                                                        @NonNull AmqpReplyContext replyContext) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor);
        builder.put("brokerType", "RABBIT");
        builder.put("exceptionClass", cause.getClass().getName());

        String causeMessage = cause.getMessage();
        builder.putTruncatedIfPresent("causeMessage", causeMessage, MAX_CAUSE_MESSAGE_LENGTH);

        builder.withRootCause(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH, MAX_CAUSE_MESSAGE_LENGTH);

        if (replyContext.replyCode() != null) {
            builder.put("amqpReplyCode", replyContext.replyCode().toString());
        }
        builder.putTruncatedIfPresent("amqpReplyText", replyContext.replyText(), MAX_REPLY_TEXT_LENGTH);

        if (errorType == RabbitErrorType.CONNECTION_FAILURE || errorType == RabbitErrorType.TIMEOUT) {
            Throwable network = MessagingMetadataUtils.unwrapNetworkCause(cause, CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH);
            if (network != cause) {
                builder.put("networkCauseClass", network.getClass().getName());
                builder.putTruncatedIfPresent("networkCauseMessage", network.getMessage(), MAX_CAUSE_MESSAGE_LENGTH);
            }
        }

        if (includeBrokerCoordinates) {
            MessagingMetadataUtils.enrichBuilderWithMessageHeaders(
                    cause,
                    MessagingMetadataUtils.RABBIT_HEADER_TO_LOGICAL_KEY,
                    builder
            );
        } else if (errorType == RabbitErrorType.REJECT_NO_REQUEUE) {
            MessagingMetadataUtils.enrichWithMessageHeaders(
                    cause,
                    MessagingMetadataUtils.RABBIT_HEADER_TO_LOGICAL_KEY,
                    (logicalKey, value) -> {
                        if ("queue".equals(logicalKey)) {
                            builder.put("queue", value);
                        }
                    });
        }

        return builder.withStackHint().build();
    }

    /**
     * Семантические типы AMQP/RabbitMQ ошибок. Группы выровнены по AMQP 0-9-1
     * reply codes и Spring AMQP exception hierarchy.
     */
    private enum RabbitErrorType implements ErrorClassification {

        /**
         * Аутентификация не удалась ({@code AmqpAuthenticationException}, AMQP 530).
         * HTTP 500, не 401 — security-through-obscurity (см. также {@code KafkaBrokerErrorType.AUTH_FAILURE}).
         */
        AUTH_FAILURE(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "RabbitMQ authentication failed. The configured credentials are invalid or the auth provider is unreachable."),

        /**
         * Авторизация: операция запрещена ACL'ом (AMQP 403 access-refused, 530 not-allowed).
         */
        ACCESS_REFUSED(
                HttpStatus.FORBIDDEN,
                Retryability.NOT_RETRYABLE,
                "RabbitMQ denied access to the requested resource."),

        /**
         * Очередь / exchange / binding не существует (AMQP 404 not-found).
         */
        RESOURCE_NOT_FOUND(
                HttpStatus.NOT_FOUND,
                Retryability.NOT_RETRYABLE,
                "The requested RabbitMQ resource (queue/exchange) does not exist."),

        /**
         * Очередь занята другим consumer'ом в exclusive-режиме (AMQP 405 resource-locked).
         */
        RESOURCE_LOCKED(
                HttpStatus.CONFLICT,
                Retryability.RETRYABLE,
                "The RabbitMQ resource is locked by another exclusive consumer."),

        /**
         * Очередь / exchange объявлены с конфликтующими параметрами (AMQP 406 precondition-failed).
         */
        PRECONDITION_FAILED(
                HttpStatus.CONFLICT,
                Retryability.NOT_RETRYABLE,
                "RabbitMQ resource declaration conflicts with existing definition (durability/arguments mismatch)."),

        /**
         * Намеренный отказ обработки и отправка в DLQ ({@code AmqpRejectAndDontRequeueException}).
         * Семантически business-уровневый сигнал — не повторять обработку.
         */
        REJECT_NO_REQUEUE(
                HttpStatus.UNPROCESSABLE_ENTITY,
                Retryability.NOT_RETRYABLE,
                "The message was rejected and will be routed to a dead-letter queue."),

        /**
         * Ресурс брокера недоступен ({@code AmqpResourceNotAvailableException}).
         */
        RESOURCE_NOT_AVAILABLE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "RabbitMQ resource is temporarily unavailable."),

        /**
         * Брокер закрыл соединение (AMQP 320 connection-forced) — типично maintenance
         * или graceful shutdown узла кластера.
         */
        CONNECTION_FORCED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "RabbitMQ connection was forcibly closed by the broker (maintenance or graceful shutdown)."),

        /**
         * Сетевой сбой подключения ({@code AmqpConnectException} или сетевые причины
         * в cause chain).
         */
        CONNECTION_FAILURE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "Unable to connect to the RabbitMQ broker."),

        /**
         * Таймаут операции ({@code AmqpTimeoutException} или {@code SocketTimeoutException}).
         */
        TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "The RabbitMQ broker did not respond within the configured timeout."),

        /**
         * Канал в состоянии ошибки (AMQP 504 channel-error).
         */
        CHANNEL_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The RabbitMQ channel is in an erroneous state and cannot be reused."),

        /**
         * Нарушение протокола AMQP (AMQP 501 frame-error, 502 syntax-error,
         * 503 command-invalid, 505 unexpected-frame, 540 not-implemented).
         */
        PROTOCOL_VIOLATION(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "AMQP protocol violation detected by the broker. The connection cannot be recovered."),

        /**
         * Внутренняя ошибка ресурса брокера (AMQP 506 resource-error).
         */
        BROKER_RESOURCE_ERROR(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The RabbitMQ broker reported an internal resource error (memory/disk pressure)."),

        /**
         * Внутренняя ошибка брокера (AMQP 541 internal-error).
         */
        BROKER_INTERNAL_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.RETRYABLE,
                "The RabbitMQ broker reported an internal error."),

        /**
         * Generic fallback. {@code NOT_RETRYABLE} намеренно — см. логику в
         * {@code KafkaBrokerErrorType.GENERIC}.
         */
        GENERIC(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "An unclassified error occurred during RabbitMQ broker communication.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        RabbitErrorType(@NonNull HttpStatus httpStatus,
                        @NonNull Retryability retryability,
                        @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @Override @NonNull public HttpStatus httpStatus()    { return httpStatus; }
        @Override @NonNull public Retryability retryability() { return retryability; }
        @Override @NonNull public String message()           { return message; }
    }

    private record AmqpReplyContext(@Nullable Integer replyCode, @Nullable String replyText) {

        @NonNull
        static AmqpReplyContext unwrap(@NonNull Throwable exception) {
            if (SHUTDOWN_SIGNAL_EXCEPTION == null) {
                return new AmqpReplyContext(null, null);
            }

            Throwable current = exception;
            for (int depth = 0; depth < CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH; depth++) {
                if (ClassUtils.isInstanceOf(SHUTDOWN_SIGNAL_EXCEPTION, current)) {
                    return extractReplyContext(current);
                }

                Throwable next = current.getCause();
                if (next == null || next == current) {
                    return new AmqpReplyContext(null, null);
                }

                current = next;
            }

            return new AmqpReplyContext(null, null);
        }

        @NonNull
        private static AmqpReplyContext extractReplyContext(@NonNull Throwable shutdownSignal) {
            Integer[] replyCodeHolder = {null};
            String[] replyTextHolder = {null};

            ReflectionUtils.invokeMethod(shutdownSignal, "getReason", reasonObj -> {
                if (reasonObj == null) {
                    return;
                }

                ReflectionUtils.invokeMethod(reasonObj, "getReplyCode", code -> {
                    if (code instanceof Integer i) {
                        replyCodeHolder[0] = i;
                    }
                });

                ReflectionUtils.invokeMethod(reasonObj, "getReplyText", text -> {
                    if (text instanceof String s && StringUtils.hasText(s)) {
                        replyTextHolder[0] = s;
                    }
                });
            });

            return new AmqpReplyContext(replyCodeHolder[0], replyTextHolder[0]);
        }
    }
}
