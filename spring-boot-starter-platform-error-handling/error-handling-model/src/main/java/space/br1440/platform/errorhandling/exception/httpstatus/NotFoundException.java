package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «404 Not Found» (RFC&nbsp;9110, §15.5.5).
 *
 * <p>Сигнализирует, что запрошенный ресурс не существует или его существование не должно раскрываться.
 * В платформенной иерархии применяется в {@code datasource/} для отсутствия сущности по идентификатору
 * и в качестве cross-cutting маркера для прочих сценариев.
 *
 * <p>Имя пересекается с {@code jakarta.ws.rs.NotFoundException} и Spring MVC; при совместном импорте
 * используйте FQN платформенного типа.
 */
public interface NotFoundException {
}
