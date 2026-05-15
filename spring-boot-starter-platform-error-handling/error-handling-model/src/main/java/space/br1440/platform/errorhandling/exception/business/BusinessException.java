package space.br1440.platform.errorhandling.exception.business;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;

import java.util.List;
import java.util.Map;

/**
 * Семантический маркер «бизнес-операция домена».
 *
 * <p>Маркируемое исключение фиксирует нарушение прикладного правила микросервиса-потребителя
 * (доменный инвариант, состояние агрегата, политика жизненного цикла), а не сбой инфраструктуры,
 * валидации входа или интеграции. Платформа концентрирует здесь точку расширения.
 *
 * <p>Подкласс потребителя <b>обязан</b>:
 * <ul>
 *   <li>наследоваться от {@link BusinessException};</li>
 *   <li>быть {@code final};</li>
 *   <li>реализовывать ровно один HTTP-маркер из
 *       {@link space.br1440.platform.errorhandling.exception.httpstatus} либо не реализовывать ни одного
 *       (fallback 500 у {@link space.br1440.platform.errorhandling.http.DefaultHttpStatusResolver}).</li>
 * </ul>
 */
public abstract class BusinessException extends AbstractErrorResponseException {

    protected BusinessException(String message, Map<String, String> clientMetadata) {
        super(message, clientMetadata);
    }

    protected BusinessException(String message, Map<String, String> clientMetadata, Throwable cause) {
        super(message, clientMetadata, cause);
    }

    protected BusinessException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, details);
    }

    protected BusinessException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, details, cause);
    }

    protected BusinessException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata) {
        super(message, clientMetadata, diagnosticMetadata);
    }

    protected BusinessException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, cause);
    }

    protected BusinessException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, diagnosticMetadata, details);
    }

    protected BusinessException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, details, cause);
    }
}
