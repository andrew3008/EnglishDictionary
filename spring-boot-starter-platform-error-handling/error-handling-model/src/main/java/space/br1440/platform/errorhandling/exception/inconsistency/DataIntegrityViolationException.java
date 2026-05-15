package space.br1440.platform.errorhandling.exception.inconsistency;

import space.br1440.platform.errorhandling.exception.httpstatus.ConflictException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Исключение при нарушении целостности данных (ответ обычно HTTP 409).
 *
 * <p><b>Назначение:</b> нарушение ограничений БД — уникальный ключ, внешний ключ, NOT NULL и т.п.
 */
public final class DataIntegrityViolationException extends InconsistencyException implements ConflictException {

    private static final String MESSAGE_TEMPLATE = "Data integrity violation: {0}";

    public DataIntegrityViolationException(String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription));
    }

    public DataIntegrityViolationException(String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("errorDescription", errorDescription)
                .build();
    }
}
