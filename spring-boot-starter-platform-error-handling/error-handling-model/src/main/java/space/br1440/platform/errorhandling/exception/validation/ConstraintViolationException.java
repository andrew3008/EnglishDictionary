package space.br1440.platform.errorhandling.exception.validation;

import space.br1440.platform.errorhandling.exception.httpstatus.BadRequestException;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Исключение при нарушении ограничений валидации (ответ обычно HTTP 400).
 *
 * <p><b>Назначение:</b> нарушение constraint (Bean Validation и аналоги).
 * Структурированные ошибки по полям передаются через {@link ErrorEntry#getDetails()}
 * (например, {@link space.br1440.platform.errorhandling.model.error_detail.BadRequestDetail}).
 */
public final class ConstraintViolationException extends ValidationException implements BadRequestException {

    private static final String MESSAGE_TEMPLATE = "Constraint violation: {0}";

    public ConstraintViolationException(String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription));
    }

    public ConstraintViolationException(String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("errorDescription", errorDescription)
                .build();
    }
}
