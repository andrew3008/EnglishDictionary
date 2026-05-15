package space.br1440.platform.errorhandling.exception.integration;

import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Исключение при получении некорректного ответа от внешнего сервиса (по умолчанию HTTP 502).
 *
 * <p>Базовый тип намеренно <b>не {@code final}</b> — точка расширения для
 * {@link ExternalServiceBadRequestException} и {@link ExternalServiceUnauthorizedException}.
 *
 * <p>Для проксирования числового HTTP-кода upstream используйте
 * {@link #proxiedUpstream(String, String, Throwable)} — иначе сигнатура совпала бы с
 * {@code (serviceName, errorDescription, cause)}.
 * {@link space.br1440.platform.errorhandling.http.DefaultHttpStatusResolver} сначала вызывает
 * {@link #resolveProxiedHttpStatus()}.
 */
public class ExternalServiceException extends IntegrationException {

    private static final String MESSAGE_TEMPLATE = "External service {0} returned an error: {1}";

    private final String proxiedStatusCodeOrNull;

    public ExternalServiceException(String serviceName, String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName, errorDescription),
                buildClientMetadata(serviceName, errorDescription));
        this.proxiedStatusCodeOrNull = null;
    }

    public ExternalServiceException(String serviceName, String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName, errorDescription),
                buildClientMetadata(serviceName, errorDescription), cause);
        this.proxiedStatusCodeOrNull = null;
    }

    /**
     * Проксируемый ответ upstream: код и тело попадают в {@code clientMetadata}, причина — в {@code cause}.
     */
    public static ExternalServiceException proxiedUpstream(String proxiedStatusCode, String responseBody, Throwable cause) {
        return new ExternalServiceException(
                buildProxiedMessage(proxiedStatusCode, responseBody),
                buildProxiedClientMetadata(proxiedStatusCode, responseBody),
                cause,
                proxiedStatusCode == null ? null : proxiedStatusCode.trim()
        );
    }

    private ExternalServiceException(String message, Map<String, String> clientMetadata, Throwable cause, String proxiedCodeOrNull) {
        super(message, clientMetadata, cause);
        this.proxiedStatusCodeOrNull = proxiedCodeOrNull;
    }

    protected ExternalServiceException(String message, String serviceName, String errorDescription) {
        super(message, buildClientMetadata(serviceName, errorDescription));
        this.proxiedStatusCodeOrNull = null;
    }

    protected ExternalServiceException(String message, String serviceName, String errorDescription, Throwable cause) {
        super(message, buildClientMetadata(serviceName, errorDescription), cause);
        this.proxiedStatusCodeOrNull = null;
    }

    /**
     * Если задан {@code proxiedStatusCode} — возвращает распознанный {@link HttpStatus};
     * иначе для самого {@link ExternalServiceException} — {@code 502};
     * для подклассов с HTTP-маркером — {@code null} (статус выберет резолвер по маркерам).
     */
    public HttpStatus resolveProxiedHttpStatus() {
        if (proxiedStatusCodeOrNull != null && !proxiedStatusCodeOrNull.isEmpty()) {
            return parseProxiedStatus(proxiedStatusCodeOrNull);
        }

        if (getClass() == ExternalServiceException.class) {
            return HttpStatus.BAD_GATEWAY;
        }

        return null;
    }

    private static HttpStatus parseProxiedStatus(String code) {
        try {
            int numeric = Integer.parseInt(code.trim());
            HttpStatus resolved = HttpStatus.resolve(numeric);
            return resolved != null ? resolved : HttpStatus.BAD_GATEWAY;
        } catch (NumberFormatException ignored) {
            return HttpStatus.BAD_GATEWAY;
        }
    }

    static Map<String, String> buildClientMetadata(String serviceName, String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("serviceName", serviceName)
                .put("errorDescription", errorDescription)
                .build();
    }

    private static String buildProxiedMessage(String proxiedStatusCode, String responseBody) {
        String body = (responseBody == null) ? "" : truncate(responseBody, 512);
        return MessageFormat.format("Upstream HTTP status {0}: {1}",
                proxiedStatusCode == null ? "?" : proxiedStatusCode.trim(),
                body);
    }

    private static Map<String, String> buildProxiedClientMetadata(String proxiedStatusCode, String responseBody) {
        return ClientMetadataBuilder.builder()
                .put("proxiedStatusCode", proxiedStatusCode == null ? "" : proxiedStatusCode.trim())
                .put("responseBody", responseBody == null ? "" : truncate(responseBody, 2048))
                .build();
    }

    private static String truncate(String value, int maxLen) {
        if (value.length() <= maxLen) {
            return value;
        }

        return value.substring(0, maxLen);
    }
}
