package space.br1440.platform.errorhandling.exception.integration;

import space.br1440.platform.errorhandling.exception.httpstatus.ForbiddenException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Доступ запрещён (ответ обычно HTTP 403).
 */
public final class AccessDeniedException extends IntegrationException implements ForbiddenException {

    private static final String MESSAGE_TEMPLATE = "Access denied: {0}";

    public AccessDeniedException(String reasonMessage) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage));
    }

    public AccessDeniedException(String reasonMessage, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage), cause);
    }

    private static Map<String, String> buildClientMetadata(String reasonMessage) {
        return ClientMetadataBuilder.builder()
                .put("reasonMessage", reasonMessage)
                .build();
    }
}
