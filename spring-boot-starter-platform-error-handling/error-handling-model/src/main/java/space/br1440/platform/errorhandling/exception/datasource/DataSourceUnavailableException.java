package space.br1440.platform.errorhandling.exception.datasource;

import space.br1440.platform.errorhandling.exception.httpstatus.ServiceUnavailableException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Временная недоступность источника данных (ответ обычно HTTP 503).
 */
public final class DataSourceUnavailableException extends DataSourceException implements ServiceUnavailableException {

    private static final String MESSAGE_TEMPLATE = "Data source is currently unavailable: {0}";

    public DataSourceUnavailableException(String reasonMessage) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage));
    }

    public DataSourceUnavailableException(String reasonMessage, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage), cause);
    }

    private static Map<String, String> buildClientMetadata(String reasonMessage) {
        return ClientMetadataBuilder.builder()
                .put("reasonMessage", reasonMessage)
                .build();
    }
}
