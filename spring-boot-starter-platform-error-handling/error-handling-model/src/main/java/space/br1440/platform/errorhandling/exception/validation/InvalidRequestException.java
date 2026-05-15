package space.br1440.platform.errorhandling.exception.validation;

import space.br1440.platform.errorhandling.exception.httpstatus.BadRequestException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Исключение для некорректного запроса (ответ обычно HTTP 400).
 *
 * <p><b>Назначение:</b> сервер понял структуру запроса, но запрос содержит
 * некорректные параметры, недопустимые значения или нарушает ожидаемый формат.
 * Клиент не должен повторять запрос без исправлений.
 *
 * <h2>Пример</h2>
 * <pre>{@code
 * throw new InvalidRequestException("Parameter 'startDate' must be in ISO-8601 format");
 * }</pre>
 */
public final class InvalidRequestException extends ValidationException implements BadRequestException {

    private static final String MESSAGE_TEMPLATE = "Invalid request: {0}";

    public InvalidRequestException(String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription));
    }

    public InvalidRequestException(String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("errorDescription", errorDescription)
                .build();
    }
}
