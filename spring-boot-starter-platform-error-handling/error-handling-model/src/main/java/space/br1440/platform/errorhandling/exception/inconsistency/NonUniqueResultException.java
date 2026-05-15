package space.br1440.platform.errorhandling.exception.inconsistency;

import space.br1440.platform.errorhandling.exception.httpstatus.ConflictException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Исключение при неоднозначном результате (ответ обычно HTTP 409).
 *
 * <p><b>Назначение:</b> ожидалась одна сущность/запись, но найдено несколько.
 */
public final class NonUniqueResultException extends InconsistencyException implements ConflictException {

    private static final String MESSAGE_TEMPLATE = "Non-unique result: {0}";

    public NonUniqueResultException(String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription));
    }

    public NonUniqueResultException(String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("errorDescription", errorDescription)
                .build();
    }
}
