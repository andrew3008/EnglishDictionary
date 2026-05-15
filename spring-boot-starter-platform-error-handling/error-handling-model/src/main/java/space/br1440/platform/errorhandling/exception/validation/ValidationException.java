package space.br1440.platform.errorhandling.exception.validation;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;

import java.util.List;
import java.util.Map;

/**
 * Семантический маркер «нарушение контракта запроса».
 *
 * <p>Маркируемое исключение свидетельствует о том, что входные данные не соответствуют декларированной форме
 * или семантическим ограничениям API: формат, обязательность полей, диапазоны значений, согласованность
 * связанных параметров. Ответственность за исправление лежит на клиенте.
 *
 * <p>Расположен в одном пакете с реализаторами (package-by-feature). Семантическая классификация —
 * {@code instanceof} в прикладном коде и в ArchUnit-инвариантах.
 *
 * <h2>Коллизия simple name</h2>
 * Имя совпадает с {@code jakarta.validation.ValidationException}. При совместном использовании
 * Bean Validation и платформенных исключений в одном файле указывайте FQN платформенного типа
 * во избежание неоднозначного импорта.
 */
public abstract class ValidationException extends AbstractErrorResponseException {

    protected ValidationException(String message, Map<String, String> clientMetadata) {
        super(message, clientMetadata);
    }

    protected ValidationException(String message, Map<String, String> clientMetadata, Throwable cause) {
        super(message, clientMetadata, cause);
    }

    protected ValidationException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, details);
    }

    protected ValidationException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, details, cause);
    }

    protected ValidationException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata) {
        super(message, clientMetadata, diagnosticMetadata);
    }

    protected ValidationException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, cause);
    }

    protected ValidationException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, diagnosticMetadata, details);
    }

    protected ValidationException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, details, cause);
    }
}
