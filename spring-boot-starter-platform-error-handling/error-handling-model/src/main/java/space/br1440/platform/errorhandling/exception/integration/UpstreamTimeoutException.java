package space.br1440.platform.errorhandling.exception.integration;

import space.br1440.platform.errorhandling.exception.httpstatus.GatewayTimeoutException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Upstream-сервис не ответил вовремя (ответ обычно HTTP 504).
 */
public final class UpstreamTimeoutException extends IntegrationException implements GatewayTimeoutException {

    private static final String MESSAGE_TEMPLATE = "Upstream service {0} timed out: {1}";

    public UpstreamTimeoutException(String serviceName, String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName, errorDescription),
                buildClientMetadata(serviceName, errorDescription));
    }

    public UpstreamTimeoutException(String serviceName, String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName, errorDescription),
                buildClientMetadata(serviceName, errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String serviceName, String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("serviceName", serviceName)
                .put("errorDescription", errorDescription)
                .build();
    }
}
