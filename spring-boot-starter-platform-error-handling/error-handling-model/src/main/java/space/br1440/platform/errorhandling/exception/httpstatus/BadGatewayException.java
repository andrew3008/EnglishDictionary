package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «502 Bad Gateway» (RFC&nbsp;9110, §15.6.3).
 *
 * <p>Сигнализирует, что сервис, выступая шлюзом или прокси, получил некорректный ответ от upstream.
 * В платформенной иерархии — статус по умолчанию для базового
 * {@link space.br1440.platform.errorhandling.exception.integration.ExternalServiceException},
 * если проксируемый upstream-код не задан явно.
 */
public interface BadGatewayException {
}
