package space.br1440.platform.datasource.postgres.api.exception;

import space.br1440.platform.errorhandling.exception.datasource.DataSourceException;
import space.br1440.platform.errorhandling.exception.httpstatus.ServiceUnavailableException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

public class WriteDataSourceUnavailableException extends DataSourceException implements ServiceUnavailableException {

    private static final String MESSAGE_TEMPLATE = "Write DataSource is currently unavailable: {0}";

    public WriteDataSourceUnavailableException(String reasonMessage, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage), cause);
    }

    private static Map<String, String> buildClientMetadata(String reasonMessage) {
        return ClientMetadataBuilder.builder()
                .put("reasonMessage", reasonMessage)
                .put("intent", "write")
                .build();
    }
}
