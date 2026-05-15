package space.br1440.platform.errorhandling.exception.inconsistency;

import space.br1440.platform.errorhandling.exception.httpstatus.ConflictException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Исключение при попытке запустить операцию, которая уже выполняется (ответ обычно HTTP 409).
 *
 * <p><b>Назначение:</b> другая операция над тем же ресурсом уже находится в процессе выполнения.
 * Клиент должен подождать завершения текущей операции и повторить запрос позже.
 *
 * <h2>Пример</h2>
 * <pre>{@code
 * throw new ConcurrentOperationException("BALLISTICS_CALCULATION", "spacecraft-id:42");
 * }</pre>
 */
public final class ConcurrentOperationException extends InconsistencyException implements ConflictException {

    private static final String MESSAGE_TEMPLATE = "Concurrent operation {0} is already in progress for resource {1}";

    public ConcurrentOperationException(String operationType, String resourceId) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, operationType, resourceId),
                buildClientMetadata(operationType, resourceId));
    }

    public ConcurrentOperationException(String operationType, String resourceId, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, operationType, resourceId),
                buildClientMetadata(operationType, resourceId), cause);
    }

    private static Map<String, String> buildClientMetadata(String operationType, String resourceId) {
        return ClientMetadataBuilder.builder()
                .put("operationType", operationType)
                .put("resourceId", resourceId)
                .build();
    }
}
