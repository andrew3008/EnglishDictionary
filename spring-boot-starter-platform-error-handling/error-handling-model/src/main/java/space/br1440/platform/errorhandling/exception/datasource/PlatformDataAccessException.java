package space.br1440.platform.errorhandling.exception.datasource;

import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Ошибка доступа к данным на уровне запроса (без HTTP-маркера — fallback 500).
 *
 * <p>Для транзиентных сбоев пула/соединения используйте {@link DataSourceUnavailableException}.
 */
public final class PlatformDataAccessException extends DataSourceException {

    private static final String MESSAGE_TEMPLATE = "Data access error: {0}";

    public PlatformDataAccessException(String reasonMessage) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage));
    }

    public PlatformDataAccessException(String reasonMessage, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, reasonMessage), buildClientMetadata(reasonMessage), cause);
    }

    private static Map<String, String> buildClientMetadata(String reasonMessage) {
        return ClientMetadataBuilder.builder()
                .put("reasonMessage", reasonMessage)
                .build();
    }
}
