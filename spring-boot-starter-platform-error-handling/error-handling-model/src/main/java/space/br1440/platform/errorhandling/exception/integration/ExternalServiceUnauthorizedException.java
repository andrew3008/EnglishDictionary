package space.br1440.platform.errorhandling.exception.integration;

import space.br1440.platform.errorhandling.exception.httpstatus.UnauthorizedException;

import java.text.MessageFormat;

/**
 * Внешний сервис отказал из-за невалидных технических учётных данных (ответ обычно HTTP 401).
 */
public final class ExternalServiceUnauthorizedException extends ExternalServiceException implements UnauthorizedException {

    private static final String MESSAGE_TEMPLATE = "External service {0} rejected credentials: {1}";

    public ExternalServiceUnauthorizedException(String serviceName, String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName, errorDescription),
                serviceName, errorDescription);
    }

    public ExternalServiceUnauthorizedException(String serviceName, String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName, errorDescription),
                serviceName, errorDescription, cause);
    }
}
