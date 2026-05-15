package space.br1440.platform.errorhandling.exception.internal;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Fallback для неклассифицированных внутренних ошибок (без HTTP-маркера — ответ 500).
 *
 * <p>В доменном коде предпочтительнее узкие платформенные или собственные исключения с явными HTTP-маркерами.
 */
public final class InternalServerException extends AbstractErrorResponseException {

    private static final String MESSAGE_TEMPLATE = "Internal server error: {0}";

    public InternalServerException(String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription));
    }

    public InternalServerException(String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, errorDescription), buildClientMetadata(errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("errorDescription", errorDescription)
                .build();
    }
}
