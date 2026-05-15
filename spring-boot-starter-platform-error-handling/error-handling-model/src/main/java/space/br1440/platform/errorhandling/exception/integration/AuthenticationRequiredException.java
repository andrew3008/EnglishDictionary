package space.br1440.platform.errorhandling.exception.integration;

import space.br1440.platform.errorhandling.exception.httpstatus.UnauthorizedException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Запрос требует аутентификации (ответ обычно HTTP 401).
 */
public final class AuthenticationRequiredException extends IntegrationException implements UnauthorizedException {

    private static final String MESSAGE_TEMPLATE = "Authentication required: {0}";

    public AuthenticationRequiredException(String reasonMessage) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage));
    }

    public AuthenticationRequiredException(String reasonMessage, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage), cause);
    }

    private static Map<String, String> buildClientMetadata(String reasonMessage) {
        return ClientMetadataBuilder.builder()
                .put("reasonMessage", reasonMessage)
                .build();
    }
}
