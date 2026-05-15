package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «400 Bad Request» (RFC&nbsp;9110, §15.5.1).
 *
 * <p>Сигнализирует, что запрос не может быть обработан из-за нарушения синтаксиса или базового контракта
 * (отсутствует обязательное поле, нечитаемое тело, недопустимый формат). Используется в
 * {@link space.br1440.platform.errorhandling.http.DefaultHttpStatusResolver} для диспетчеризации
 * HTTP-статуса ответа.
 *
 * <p>Имя пересекается с {@code jakarta.ws.rs.BadRequestException} и
 * {@code org.springframework.web.client.HttpClientErrorException.BadRequest}; при совместном импорте
 * используйте FQN платформенного типа.
 */
public interface BadRequestException {
}
