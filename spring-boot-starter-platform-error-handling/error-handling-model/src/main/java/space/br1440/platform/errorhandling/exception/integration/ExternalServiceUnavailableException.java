package space.br1440.platform.errorhandling.exception.integration;

import space.br1440.platform.errorhandling.exception.httpstatus.ServiceUnavailableException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Внешний сервис недоступен (ответ обычно HTTP 503).
 */
public final class ExternalServiceUnavailableException extends IntegrationException implements ServiceUnavailableException {

    private static final String MESSAGE_TEMPLATE = "Service {0} is currently unavailable";

    public ExternalServiceUnavailableException(String serviceName) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName), buildClientMetadata(serviceName));
    }

    public ExternalServiceUnavailableException(String serviceName, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName), buildClientMetadata(serviceName), cause);
    }

    private static Map<String, String> buildClientMetadata(String serviceName) {
        return ClientMetadataBuilder.builder()
                .put("serviceName", serviceName)
                .build();
    }
}
