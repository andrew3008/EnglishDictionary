package space.br1440.platform.errorhandling.exception.datasource;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;

import java.util.List;
import java.util.Map;

/**
 * Семантический маркер «доступ к источнику данных».
 *
 * <p>Маркируемое исключение фиксирует сбой на уровне взаимодействия сервиса с собственным persistence-слоем
 * (база данных, кэш, объектное хранилище), включая «не найдено по идентификатору» и
 * транзиентную недоступность пула/соединения.
 *
 * <p>Граница с {@link space.br1440.platform.errorhandling.exception.inconsistency.InconsistencyException}:
 * inconsistency описывает <i>противоречие состояния</i> при попытке записи или агрегации, тогда как
 * {@code DataSourceException} — нештатное поведение самого хранилища.
 * Граница с {@link space.br1440.platform.errorhandling.exception.integration.IntegrationException}:
 * последний относится к <i>внешним</i> сервисам, тогда как datasource — к собственному источнику данных сервиса.
 *
 * <p>Семантическая классификация — {@code instanceof} в прикладном коде и в ArchUnit-инвариантах.
 */
public abstract class DataSourceException extends AbstractErrorResponseException {

    protected DataSourceException(String message, Map<String, String> clientMetadata) {
        super(message, clientMetadata);
    }

    protected DataSourceException(String message, Map<String, String> clientMetadata, Throwable cause) {
        super(message, clientMetadata, cause);
    }

    protected DataSourceException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, details);
    }

    protected DataSourceException(String message, Map<String, String> clientMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, details, cause);
    }

    protected DataSourceException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata) {
        super(message, clientMetadata, diagnosticMetadata);
    }

    protected DataSourceException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, cause);
    }

    protected DataSourceException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details) {
        super(message, clientMetadata, diagnosticMetadata, details);
    }

    protected DataSourceException(String message, Map<String, String> clientMetadata, Map<String, String> diagnosticMetadata, List<ErrorDetail> details, Throwable cause) {
        super(message, clientMetadata, diagnosticMetadata, details, cause);
    }
}
