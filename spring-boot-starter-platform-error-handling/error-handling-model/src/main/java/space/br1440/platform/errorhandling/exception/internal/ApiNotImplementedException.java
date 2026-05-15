package space.br1440.platform.errorhandling.exception.internal;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.exception.httpstatus.NotImplementedException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Функциональность не реализована (ответ обычно HTTP 501).
 *
 * <p>Без семантического маркера — см. {@code internal/package-info.java}.
 */
public final class ApiNotImplementedException extends AbstractErrorResponseException implements NotImplementedException {

    private static final String MESSAGE_TEMPLATE = "API or operation is not implemented: {0}";

    public ApiNotImplementedException(String reasonMessage) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage));
    }

    public ApiNotImplementedException(String reasonMessage, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage), cause);
    }

    private static Map<String, String> buildClientMetadata(String reasonMessage) {
        return ClientMetadataBuilder.builder()
                .put("reasonMessage", reasonMessage)
                .build();
    }
}
