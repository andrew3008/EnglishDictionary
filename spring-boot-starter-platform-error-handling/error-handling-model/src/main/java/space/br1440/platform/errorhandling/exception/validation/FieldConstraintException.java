package space.br1440.platform.errorhandling.exception.validation;

import space.br1440.platform.errorhandling.exception.httpstatus.BadRequestException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Исключение при нарушении ограничения на поле запроса (ответ обычно HTTP 400).
 *
 * <p><b>Назначение:</b> значение поля нарушает бизнес-ограничение:
 * длина, формат, диапазон, допустимые значения, уникальность в контексте запроса.
 *
 * <h2>Примеры</h2>
 * <pre>{@code
 * throw new FieldConstraintException("channelName", "length must be between 1 and 255");
 * throw new FieldConstraintException("priority", "must be a positive integer");
 * throw new FieldConstraintException("email", "does not match required format: user@domain");
 * }</pre>
 */
public final class FieldConstraintException extends ValidationException implements BadRequestException {

    private static final String MESSAGE_TEMPLATE = "Field {0} violates constraint: {1}";

    public FieldConstraintException(String fieldName, String constraintDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, fieldName, constraintDescription),
                buildClientMetadata(fieldName, constraintDescription));
    }

    public FieldConstraintException(String fieldName, String constraintDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, fieldName, constraintDescription),
                buildClientMetadata(fieldName, constraintDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String fieldName, String constraintDescription) {
        return ClientMetadataBuilder.builder()
                .put("fieldName", fieldName)
                .put("constraintDescription", constraintDescription)
                .build();
    }
}
