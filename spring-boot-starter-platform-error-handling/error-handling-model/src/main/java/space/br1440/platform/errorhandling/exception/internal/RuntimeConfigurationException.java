package space.br1440.platform.errorhandling.exception.internal;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.startup.PlatformConfigurationException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Ошибка конфигурации, обнаруженная в runtime (без HTTP-маркера — fallback 500).
 *
 * <p>Для ошибок на старте приложения используйте {@link PlatformConfigurationException}.
 */
public final class RuntimeConfigurationException extends AbstractErrorResponseException {

    private static final String MESSAGE_TEMPLATE = "Runtime configuration error: {0}";

    public RuntimeConfigurationException(String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription));
    }

    public RuntimeConfigurationException(String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("errorDescription", errorDescription)
                .build();
    }
}
