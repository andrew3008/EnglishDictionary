package space.br1440.platform.errorhandling.core.strategy.cause.integration;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Стратегия обработки WebSocket-исключений как первопричины бизнес-исключений.
 *
 * <p>Покрывает два стека WebSocket API, используемых в Spring Boot приложениях:
 *
 * <h2>Поддерживаемые исключения</h2>
 * <pre>
 *   org.springframework.web.socket
 *   └── WebSocketException → 503  SPRING_WEBSOCKET    retryable=true
 *
 *   javax.websocket / jakarta.websocket
 *   ├── DeploymentException → 503  DEPLOYMENT_FAILURE  retryable=false
 *   └── DecodeException / EncodeException → 502  MESSAGE_CODEC_ERROR retryable=false
 * </pre>
 *
 * <h2>Безопасность (CWE-209)</h2>
 * <p>Сообщения исключений намеренно <b>не включаются</b> в metadata ответа — могут содержать:
 * <ul>
 *   <li>URI WebSocket endpoint'а с внутренними адресами ({@code ws://10.0.1.42:8080/...})</li>
 *   <li>Причину отказа deployment (версия протокола, конфигурация контейнера)</li>
 *   <li>Subprotocol negotiation details</li>
 * </ul>
 */
public class WebSocketExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final ClassLoader LOADER = WebSocketExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> SPRING_WS_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.web.socket.WebSocketException", LOADER);

    // Jakarta WebSocket (Servlet 5.0+ / Spring Boot 3.x)
    @Nullable
    private static final Class<?> JAKARTA_DEPLOYMENT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("jakarta.websocket.DeploymentException", LOADER);
    @Nullable
    private static final Class<?> JAKARTA_DECODE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("jakarta.websocket.DecodeException", LOADER);
    @Nullable
    private static final Class<?> JAKARTA_ENCODE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("jakarta.websocket.EncodeException", LOADER);

    // javax.websocket (legacy Servlet < 5.0 / Spring Boot 2.x)
    @Nullable
    private static final Class<?> JAVAX_DEPLOYMENT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("javax.websocket.DeploymentException", LOADER);
    @Nullable
    private static final Class<?> JAVAX_DECODE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("javax.websocket.DecodeException", LOADER);
    @Nullable
    private static final Class<?> JAVAX_ENCODE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("javax.websocket.EncodeException", LOADER);

    public WebSocketExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.WEBSOCKET, stackHintExtractor);
    }

    /**
     * WebSocket-ошибки на уровне инфраструктуры → всегда {@link LogPolicy#ERROR}.
     * Каждая WebSocket-ошибка означает потерю real-time соединения с upstream-сервисом,
     * что требует немедленного внимания операционной команды.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.ERROR;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return isWebSocketException(cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        WebSocketErrorType type = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("websocket")
                .diagnosticMetadata(buildDiagnosticMetadata(cause))
                .build();
        details.add(info);

        return type.toResolution(details);
    }

    @NonNull
    private static WebSocketErrorType classify(@NonNull Throwable throwable) {
        if (ClassUtils.isInstanceOf(JAKARTA_DECODE_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(JAVAX_DECODE_EXCEPTION, throwable)) {
            return WebSocketErrorType.MESSAGE_CODEC_ERROR;
        }

        if (ClassUtils.isInstanceOf(JAKARTA_ENCODE_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(JAVAX_ENCODE_EXCEPTION, throwable)) {
            return WebSocketErrorType.MESSAGE_CODEC_ERROR;
        }

        if (ClassUtils.isInstanceOf(JAKARTA_DEPLOYMENT_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(JAVAX_DEPLOYMENT_EXCEPTION, throwable)) {
            return WebSocketErrorType.DEPLOYMENT_FAILURE;
        }

        if (ClassUtils.isInstanceOf(SPRING_WS_EXCEPTION, throwable)) {
            return WebSocketErrorType.SPRING_WEBSOCKET;
        }

        throw new AssertionError(
                "WebSocketExceptionStrategy.classify() called for unsupported exception: "
                        + throwable.getClass().getName()
                        + ". If this fires, isWebSocketException() accepted a type that classify() cannot handle.");
    }

    private static boolean isWebSocketException(@NonNull Throwable throwable) {
        return ClassUtils.isInstanceOf(SPRING_WS_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(JAKARTA_DEPLOYMENT_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(JAKARTA_DECODE_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(JAKARTA_ENCODE_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(JAVAX_DEPLOYMENT_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(JAVAX_DECODE_EXCEPTION, throwable)
                || ClassUtils.isInstanceOf(JAVAX_ENCODE_EXCEPTION, throwable);
    }

    /**
     * <p>CWE-209: намеренно <b>не включаются</b>:
     * <ul>
     *   <li>URI WebSocket endpoint'а — содержит внутренние адреса</li>
     *   <li>Сообщение исключения — может содержать subprotocol, headers, IP</li>
     *   <li>HTTP Upgrade response details</li>
     * </ul>
     */
    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause) {
        DiagnosticMetadataBuilder builder = diagnosticMetadataFor(cause);
        enrichWsCloseCodeIfPresent(builder, cause);
        return builder.withStackHint().build();
    }

    /**
     * Добавляет в metadata числовой код закрытия WebSocket (RFC 6455), если API исключения его предоставляет.
     * Это безопаснее текстовых сообщений: не раскрывает URI, заголовки и subprotocol negotiation.
     */
    private static void enrichWsCloseCodeIfPresent(@NonNull DiagnosticMetadataBuilder builder,
                                                   @NonNull Throwable cause) {
        ReflectionUtils.invokeMethod(cause, "getCloseStatus", closeStatus -> {
            if (closeStatus != null) {
                ReflectionUtils.invokeMethod(closeStatus, "getCode", code -> {
                    if (code instanceof Integer i) {
                        builder.put("wsCloseCode", i.toString());
                    }
                });
            }
        });
    }

    private enum WebSocketErrorType implements ErrorClassification {
        SPRING_WEBSOCKET(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The WebSocket connection to an upstream service failed. " +
                        "The service may be temporarily unavailable."),

        DEPLOYMENT_FAILURE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The WebSocket endpoint deployment or connection upgrade failed. " +
                        "This indicates a configuration or compatibility issue."),

        MESSAGE_CODEC_ERROR(
                HttpStatus.BAD_GATEWAY,
                Retryability.NOT_RETRYABLE,
                "A WebSocket message could not be encoded or decoded. " +
                        "The message format may be incompatible with the expected contract."),

        GENERIC(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "An unexpected WebSocket error occurred while communicating with an upstream service.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        WebSocketErrorType(@NonNull HttpStatus httpStatus,
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
