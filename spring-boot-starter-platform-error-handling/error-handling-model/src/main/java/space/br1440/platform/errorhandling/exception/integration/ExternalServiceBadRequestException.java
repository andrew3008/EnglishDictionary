package space.br1440.platform.errorhandling.exception.integration;

import space.br1440.platform.errorhandling.exception.httpstatus.BadRequestException;

import java.text.MessageFormat;

/**
 * Внешний сервис отклонил запрос как невалидный (ответ обычно HTTP 400).
 *
 * <p>С точки зрения клиента приложения виноват интеграционный слой, а не конечный пользователь.
 */
public final class ExternalServiceBadRequestException extends ExternalServiceException implements BadRequestException {

    private static final String MESSAGE_TEMPLATE = "External service {0} rejected request as invalid: {1}";

    public ExternalServiceBadRequestException(String serviceName, String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName, errorDescription),
                serviceName, errorDescription);
    }

    public ExternalServiceBadRequestException(String serviceName, String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, serviceName, errorDescription),
                serviceName, errorDescription, cause);
    }
}
