package space.br1440.platform.datasource.postgres.api.exception;

import space.br1440.platform.errorhandling.exception.datasource.DataSourceException;
import space.br1440.platform.errorhandling.exception.httpstatus.ServiceUnavailableException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

public class ReadDataSourceUnavailableException extends DataSourceException implements ServiceUnavailableException {

    private static final String MESSAGE_TEMPLATE = "Read DataSource is currently unavailable: {0}";

    public ReadDataSourceUnavailableException(String reasonMessage, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage), cause);
    }

    private static Map<String, String> buildClientMetadata(String reasonMessage) {
        return ClientMetadataBuilder.builder()
                .put("reasonMessage", reasonMessage)
                .put("intent", "read")
                .build();
    }
}
