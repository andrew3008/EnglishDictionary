package space.br1440.platform.errorhandling.exception.inconsistency;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;

import java.util.List;
import java.util.Map;

/**
 * Семантический маркер «несогласованность состояния».
 *
 * <p>Маркируемое исключение фиксирует ситуацию, когда корректный по форме запрос не может быть применён
 * из-за противоречия с уже существующим состоянием системы: дубликат уникального ключа, конкурентная
 * модификация (optimistic lock), нарушение ссылочной целостности, неожиданно неуникальный результат
 * операции, ожидающей единственный объект.
 *
 * <p>Граница с {@link space.br1440.platform.errorhandling.exception.datasource.DataSourceException}:
 * последний описывает <i>доступ</i> к хранилищу, тогда как {@code InconsistencyException} — <i>исход</i>
 * операции записи или агрегации. Граница с
 * {@link space.br1440.platform.errorhandling.exception.validation.ValidationException}:
 * валидация отвечает за <i>форму запроса</i>, inconsistency — за <i>состояние данных</i>.
 *
 * <p>Семантическая классификация — {@code instanceof} в прикладном коде и в ArchUnit-инвариантах.
 */
public abstract class InconsistencyException extends AbstractErrorResponseException {

    protected InconsistencyException(String message, Map<String, String> clientMetadata) {
        super(message, clientMetadata);
    }

    protected InconsistencyException(String message, Map<String, String> clientMetadata, Throwable cause) {
        super(message, clientMetadata, cause);
    }

    protected InconsistencyException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, details);
    }

    protected InconsistencyException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, details, cause);
    }

    protected InconsistencyException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata) {
        super(message, clientMetadata, diagnosticMetadata);
    }

    protected InconsistencyException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, cause);
    }

    protected InconsistencyException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, diagnosticMetadata, details);
    }

    protected InconsistencyException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, details, cause);
    }
}
