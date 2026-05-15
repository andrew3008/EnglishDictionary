package space.br1440.platform.errorhandling.exception.validation;

import space.br1440.platform.errorhandling.exception.httpstatus.UnprocessableException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Исключение для семантически необрабатываемого запроса (ответ обычно HTTP 422).
 *
 * <p><b>Назначение:</b> сервер понял запрос и синтаксис корректен, но не может выполнить
 * инструкции из-за нарушения бизнес-инварианта, зависимости между сущностями или
 * недопустимого состояния данных.
 *
 * <p>Отличие от {@link InvalidRequestException} (400):
 * формат и тип данных верны, но семантика нарушена.
 *
 * <h2>Примеры</h2>
 * <pre>{@code
 * throw new UnprocessableEntityException("Cannot delete almanac: it is referenced by active calculations");
 * throw new UnprocessableEntityException("Maneuver interval overlaps with existing burn window");
 * }</pre>
 */
public final class UnprocessableEntityException extends ValidationException implements UnprocessableException {

    private static final String MESSAGE_TEMPLATE = "Request cannot be processed: {0}";

    public UnprocessableEntityException(String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription));
    }

    public UnprocessableEntityException(String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("errorDescription", errorDescription)
                .build();
    }
}
