package space.br1440.platform.errorhandling.exception.internal;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.exception.validation.InvalidRequestException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Сбой серверной конвертации данных (без HTTP-маркера — fallback 500).
 *
 * <p>Отличие от {@link InvalidRequestException}: там виноват клиентский ввод (обычно 400).
 */
public final class DataConversionException extends AbstractErrorResponseException {

    private static final String MESSAGE_TEMPLATE = "Failed to convert data from {0} to {1}: {2}";

    public DataConversionException(String sourceFormat, String targetFormat, String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, sourceFormat, targetFormat, errorDescription),
                buildClientMetadata(sourceFormat, targetFormat, errorDescription));
    }

    public DataConversionException(String sourceFormat, String targetFormat, String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, sourceFormat, targetFormat, errorDescription),
                buildClientMetadata(sourceFormat, targetFormat, errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String sourceFormat, String targetFormat, String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("sourceFormat", sourceFormat)
                .put("targetFormat", targetFormat)
                .put("errorDescription", errorDescription)
                .build();
    }
}
