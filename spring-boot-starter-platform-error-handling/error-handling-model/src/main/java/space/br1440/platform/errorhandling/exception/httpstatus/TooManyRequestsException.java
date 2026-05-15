package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «429 Too Many Requests» (RFC&nbsp;6585, §4).
 *
 * <p>Сигнализирует превышение клиентом установленных квот частоты обращений. В платформенной иерархии
 * используется {@link space.br1440.platform.errorhandling.exception.integration.RateLimitExceededException}
 * для отражения отказа rate limit'а на стороне интеграционного контура.
 */
public interface TooManyRequestsException {
}
