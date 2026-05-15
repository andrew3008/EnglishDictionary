package space.br1440.platform.errorhandling.exception.inconsistency;

import space.br1440.platform.errorhandling.exception.httpstatus.ConflictException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Исключение при попытке создать ресурс с ключом, который уже существует (ответ обычно HTTP 409).
 *
 * <h2>Пример</h2>
 * <pre>{@code
 * throw new DuplicateKeyException("channel", "code", channelCode);
 * }</pre>
 */
public final class DuplicateKeyException extends InconsistencyException implements ConflictException {

    private static final String MESSAGE_TEMPLATE = "Resource {0} with key {1}={2} already exists";

    public DuplicateKeyException(String resourceType, String keyName, String keyValue) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, resourceType, keyName, keyValue),
                buildClientMetadata(resourceType, keyName, keyValue));
    }

    public DuplicateKeyException(String resourceType, String keyName, String keyValue, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, resourceType, keyName, keyValue),
                buildClientMetadata(resourceType, keyName, keyValue), cause);
    }

    private static Map<String, String> buildClientMetadata(String resourceType, String keyName, String keyValue) {
        return ClientMetadataBuilder.builder()
                .put("resourceType", resourceType)
                .put("keyName", keyName)
                .put("keyValue", keyValue)
                .build();
    }
}
