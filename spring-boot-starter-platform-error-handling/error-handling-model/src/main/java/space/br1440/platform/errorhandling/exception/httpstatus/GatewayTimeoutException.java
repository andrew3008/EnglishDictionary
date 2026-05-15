package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «504 Gateway Timeout» (RFC&nbsp;9110, §15.6.5).
 *
 * <p>Сигнализирует, что сервис не получил ответ от upstream в отведённое время. В платформенной иерархии
 * применяется в {@link space.br1440.platform.errorhandling.exception.integration.UpstreamTimeoutException}.
 */
public interface GatewayTimeoutException {
}
