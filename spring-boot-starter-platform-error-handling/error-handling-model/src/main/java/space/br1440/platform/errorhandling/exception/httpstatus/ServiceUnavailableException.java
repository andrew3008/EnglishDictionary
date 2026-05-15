package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «503 Service Unavailable» (RFC&nbsp;9110, §15.6.4).
 *
 * <p>Сигнализирует, что сервис временно не может обработать запрос вследствие перегрузки, обслуживания
 * или недоступности зависимости. В платформенной иерархии применяется как для собственного persistence-слоя
 * ({@link space.br1440.platform.errorhandling.exception.datasource.DataSourceUnavailableException}),
 * так и для интеграционных сценариев
 * ({@link space.br1440.platform.errorhandling.exception.integration.ExternalServiceUnavailableException},
 * {@link space.br1440.platform.errorhandling.exception.integration.CircuitBreakerOpenException}).
 */
public interface ServiceUnavailableException {
}
