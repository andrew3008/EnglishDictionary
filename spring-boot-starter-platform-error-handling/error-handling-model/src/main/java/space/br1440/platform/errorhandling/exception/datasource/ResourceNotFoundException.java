package space.br1440.platform.errorhandling.exception.datasource;

import space.br1440.platform.errorhandling.exception.httpstatus.NotFoundException;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;

/**
 * Ресурс не найден по идентификатору (ответ обычно HTTP 404).
 */
public final class ResourceNotFoundException extends DataSourceException implements NotFoundException {

    private static final String MESSAGE_TEMPLATE = "Resource {0} with id {1} not found";

    public ResourceNotFoundException(String resourceType, String resourceId) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, resourceType, resourceId),
                buildClientMetadata(resourceType, resourceId));
    }

    public ResourceNotFoundException(String resourceType, String resourceId, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, resourceType, resourceId),
                buildClientMetadata(resourceType, resourceId), cause);
    }

    public ResourceNotFoundException(String resourceType, String resourceId, List<ErrorDetail> details) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, resourceType, resourceId),
                buildClientMetadata(resourceType, resourceId), details);
    }

    public ResourceNotFoundException(String resourceType, String resourceId, List<ErrorDetail> details, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, resourceType, resourceId),
                buildClientMetadata(resourceType, resourceId), details, cause);
    }

    private static Map<String, String> buildClientMetadata(String resourceType, String resourceId) {
        return ClientMetadataBuilder.builder()
                .put("resourceType", resourceType)
                .put("resourceId", resourceId)
                .build();
    }
}
