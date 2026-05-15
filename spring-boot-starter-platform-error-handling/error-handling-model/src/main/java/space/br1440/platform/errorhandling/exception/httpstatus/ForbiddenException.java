package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «403 Forbidden» (RFC&nbsp;9110, §15.5.4).
 *
 * <p>Сигнализирует, что аутентификация выполнена, но действие запрещено политикой доступа.
 * В отличие от 401, повторная попытка с другими учётными данными не предполагается.
 */
public interface ForbiddenException {
}
