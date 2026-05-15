package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «422 Unprocessable Content» (RFC&nbsp;9110, §15.5.21).
 *
 * <p>Сигнализирует, что запрос синтаксически корректен и был успешно разобран, но семантически невалиден:
 * нарушены прикладные ограничения полей, бизнес-правила формы данных, согласованность связанных параметров.
 * В отличие от 400, проблема обнаружена не парсером, а валидатором.
 *
 * <p>Имя сокращено относительно RFC ({@code UnprocessableException} вместо {@code UnprocessableEntityException}),
 * чтобы избежать коллизии с concrete-классом
 * {@link space.br1440.platform.errorhandling.exception.validation.UnprocessableEntityException}.
 */
public interface UnprocessableException {
}
