package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «409 Conflict» (RFC&nbsp;9110, §15.5.10).
 *
 * <p>Сигнализирует, что запрос корректен по форме, но не может быть применён из-за противоречия с текущим
 * состоянием ресурса: конкурентная модификация, нарушение уникальности, нарушение целостности.
 * В платформенной иерархии используется во всех concrete-классах пакета
 * {@link space.br1440.platform.errorhandling.exception.inconsistency}.
 */
public interface ConflictException {
}
