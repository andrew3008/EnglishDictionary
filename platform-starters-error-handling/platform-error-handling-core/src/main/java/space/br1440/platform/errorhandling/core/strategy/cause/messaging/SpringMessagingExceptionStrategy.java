package space.br1440.platform.errorhandling.core.strategy.cause.messaging;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.PathSanitizer;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Стратегия классификации исключений {@code org.springframework.messaging.*}
 * как первопричины бизнес-исключений.
 *
 * <p>Иерархии {@code MessagingException} и broker-специфичных исключений
 * ({@code KafkaException}, {@code AmqpException}) <b>не пересекаются</b>: они
 * наследуются от {@code RuntimeException}, не друг от друга. Поэтому defensive
 * guard ({@code instanceof MessagingException AND NOT KafkaException/AmqpException})
 * не нужен — порядок стратегий обеспечивает корректное диспатчирование.
 *
 * <h2>Поддерживаемые исключения</h2>
 * <pre>
 *   org.springframework.messaging
 *   ├── MessageDeliveryException                             → 503  MESSAGE_DELIVERY_FAILURE     RETRYABLE
 *   ├── converter.MessageConversionException                 → 400  MESSAGE_CONVERSION_ERROR     NOT_RETRYABLE
 *   ├── MessageHandlingException                             → 500  MESSAGE_HANDLING_ERROR       NOT_RETRYABLE
 *   └── simp.annotation.support.MissingSessionUserException  → 401  SESSION_AUTH_REQUIRED        NOT_RETRYABLE
 * </pre>
 *
 * <h2>Безопасность (CWE-209)</h2>
 *
 * <h3>Whitelist системных заголовков</h3>
 * <p>В metadata намеренно <b>не включаются</b>:
 * <ul>
 *   <li>{@code failedMessage.payload} — может содержать чувствительные пользовательские данные;</li>
 *   <li>{@code failedMessage.headers} целиком — могут содержать Authorization/tokens;</li>
 *   <li>полный текст {@code exception.getMessage()} — STOMP/Kafka parser может включить
 *       сырые фрагменты frame/record с пользовательским payload.</li>
 * </ul>
 */
public class SpringMessagingExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final ClassLoader LOADER = SpringMessagingExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> MESSAGE_DELIVERY_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.messaging.MessageDeliveryException", LOADER);

    @Nullable
    private static final Class<?> MESSAGE_CONVERSION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.messaging.converter.MessageConversionException", LOADER);

    @Nullable
    private static final Class<?> MESSAGE_HANDLING_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.messaging.MessageHandlingException", LOADER);

    @Nullable
    private static final Class<?> MISSING_SESSION_USER_EXCEPTION =
            ReflectionUtils.loadOptionalClass(
                    "org.springframework.messaging.simp.annotation.support.MissingSessionUserException", LOADER);

    private final boolean includeBrokerCoordinates;

    public SpringMessagingExceptionStrategy(boolean includeBrokerCoordinates,
                                            @NonNull StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.SPRING_MESSAGING, stackHintExtractor);
        this.includeBrokerCoordinates = includeBrokerCoordinates;
    }

    /**
     * Messaging-ошибки на транспортном уровне — источник 5xx/4xx, требуют {@link LogPolicy#ERROR}
     * для 5xx (broker/listener outage, handling failure) и {@link LogPolicy#WARN} для 4xx
     * (conversion/auth).
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

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return isSpringMessagingException(cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        SpringMessagingErrorType type = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("messaging")
                .diagnosticMetadata(buildDiagnosticMetadata(cause))
                .build();
        details.add(info);

        return type.toResolution(details);
    }

    @NonNull
    private static SpringMessagingErrorType classify(@NonNull Throwable throwable) {
        if (ClassUtils.isInstanceOf(MISSING_SESSION_USER_EXCEPTION, throwable)) {
            return SpringMessagingErrorType.SESSION_AUTH_REQUIRED;
        }

        if (ClassUtils.isInstanceOf(MESSAGE_CONVERSION_EXCEPTION, throwable)) {
            return SpringMessagingErrorType.MESSAGE_CONVERSION_ERROR;
        }

        if (ClassUtils.isInstanceOf(MESSAGE_DELIVERY_EXCEPTION, throwable)) {
            return SpringMessagingErrorType.MESSAGE_DELIVERY_FAILURE;
        }

        if (ClassUtils.isInstanceOf(MESSAGE_HANDLING_EXCEPTION, throwable)) {
            return SpringMessagingErrorType.MESSAGE_HANDLING_ERROR;
        }

        throw new AssertionError(
                "SpringMessagingExceptionStrategy.classify() called for unsupported exception: "
                        + throwable.getClass().getName()
                        + ". If this fires, isSpringMessagingException() accepted a type that classify() cannot handle.");
    }

    private static boolean isSpringMessagingException(@NonNull Throwable throwable) {
        return ClassUtils.isInstanceOf(MESSAGE_DELIVERY_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(MESSAGE_CONVERSION_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(MESSAGE_HANDLING_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(MISSING_SESSION_USER_EXCEPTION, throwable);
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause) {
        DiagnosticMetadataBuilder builder = diagnosticMetadataFor(cause);

        if (includeBrokerCoordinates) {
            enrichWithMessagingHeaders(builder, cause);
        }

        return builder.withStackHint().build();
    }

    private static void enrichWithMessagingHeaders(@NonNull DiagnosticMetadataBuilder builder,
                                                   @NonNull Throwable cause) {
        ReflectionUtils.invokeMethod(cause, "getFailedMessage", failedMessage -> {
            if (failedMessage == null) {
                return;
            }

            ReflectionUtils.invokeMethod(failedMessage, "getHeaders", headersObj -> {
                if (!(headersObj instanceof Map<?, ?> headers)) {
                    return;
                }

                for (String headerKey : MessagingMetadataUtils.SPRING_MSG_SAFE_HEADERS) {
                    extractHeaderString(headers, headerKey, value ->
                            builder.put(headerKey, value));
                }

                extractHeaderString(headers, "simpSessionId", sessionId ->
                        builder.put("simpSessionId", sessionId));

                extractHeaderString(headers, "simpDestination", destination ->
                        builder.put("simpDestination", PathSanitizer.sanitize(destination)));

                extractHeaderString(headers, "stompCommand", command ->
                        builder.put("stompCommand", command));
            });
        });
    }

    private static void extractHeaderString(@NonNull Map<?, ?> headers,
                                            @NonNull String key,
                                            @NonNull java.util.function.Consumer<String> consumer) {
        Object value = headers.get(key);
        if (value == null) {
            return;
        }

        String stringValue = (value instanceof String s) ? s : String.valueOf(value);
        if (StringUtils.hasText(stringValue)) {
            consumer.accept(stringValue);
        }
    }

    private enum SpringMessagingErrorType implements ErrorClassification {

        SESSION_AUTH_REQUIRED(
                HttpStatus.UNAUTHORIZED,
                Retryability.NOT_RETRYABLE,
                "Messaging session requires an authenticated user, but none was resolved."),

        MESSAGE_CONVERSION_ERROR(
                HttpStatus.BAD_REQUEST,
                Retryability.NOT_RETRYABLE,
                "Message payload could not be converted to/from the target type. " +
                        "The message format is incompatible with the expected contract."),

        MESSAGE_DELIVERY_FAILURE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "Message delivery failed. The destination channel or broker " +
                        "may be temporarily unavailable."),

        MESSAGE_HANDLING_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "Message handling failed inside a message-mapping method. " +
                        "This indicates a server-side failure.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        SpringMessagingErrorType(@NonNull HttpStatus httpStatus,
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
