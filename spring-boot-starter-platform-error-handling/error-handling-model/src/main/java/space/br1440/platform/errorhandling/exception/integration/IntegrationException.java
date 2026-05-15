package space.br1440.platform.errorhandling.exception.integration;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;

import java.util.List;
import java.util.Map;

/**
 * Семантический маркер «сбой интеграционного контура».
 *
 * <p>Маркируемое исключение свидетельствует о деградации <i>между</i> сервисом и его внешними зависимостями:
 * недоступность upstream, сетевые таймауты, разорванный circuit breaker, отказ rate limit'а,
 * неуспешная аутентификация или авторизация на стороне внешней инфраструктуры. С точки зрения вызывающего
 * клиента ответственность лежит на интеграционном слое, а не на пользователе.
 *
 * <p>Граница с {@link space.br1440.platform.errorhandling.exception.business.BusinessException}:
 * прикладные авторизационные решения, проверяемые внутри домена сервиса, относятся к {@code BusinessException},
 * а не к {@code IntegrationException}; последний фиксирует отказ <i>вызываемой</i> инфраструктуры.
 *
 * <p>Семантическая классификация — {@code instanceof} в прикладном коде и в ArchUnit-инвариантах.
 */
public abstract class IntegrationException extends AbstractErrorResponseException {

    protected IntegrationException(String message, Map<String, String> clientMetadata) {
        super(message, clientMetadata);
    }

    protected IntegrationException(String message, Map<String, String> clientMetadata, Throwable cause) {
        super(message, clientMetadata, cause);
    }

    protected IntegrationException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, details);
    }

    protected IntegrationException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, details, cause);
    }

    protected IntegrationException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata) {
        super(message, clientMetadata, diagnosticMetadata);
    }

    protected IntegrationException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, cause);
    }

    protected IntegrationException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, diagnosticMetadata, details);
    }

    protected IntegrationException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, details, cause);
    }
}
