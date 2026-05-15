package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «401 Unauthorized» (RFC&nbsp;9110, §15.5.2).
 *
 * <p>Сигнализирует, что запрос не содержит валидных аутентификационных учётных данных или они отвергнуты.
 * В платформенной иерархии применяется на стороне интеграционного контура
 * ({@link space.br1440.platform.errorhandling.exception.integration.AuthenticationRequiredException},
 * {@link space.br1440.platform.errorhandling.exception.integration.ExternalServiceUnauthorizedException}).
 */
public interface UnauthorizedException {
}
