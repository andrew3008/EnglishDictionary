package space.br1440.platform.errorhandling.exception.datasource;

import space.br1440.platform.errorhandling.exception.httpstatus.NotFoundException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Объект по бизнес-ключу не найден (ответ обычно HTTP 404).
 */
public final class ObjectNotFoundException extends DataSourceException implements NotFoundException {

    private static final String MESSAGE_TEMPLATE = "Object with key {0} does not exist";

    public ObjectNotFoundException(String objectKey) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, objectKey), buildClientMetadata(objectKey));
    }

    public ObjectNotFoundException(String objectKey, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, objectKey), buildClientMetadata(objectKey), cause);
    }

    private static Map<String, String> buildClientMetadata(String objectKey) {
        return ClientMetadataBuilder.builder()
                .put("objectKey", objectKey)
                .build();
    }
}
