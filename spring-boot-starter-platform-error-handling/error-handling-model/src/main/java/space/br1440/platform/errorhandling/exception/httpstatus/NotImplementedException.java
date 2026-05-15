package space.br1440.platform.errorhandling.exception.httpstatus;

/**
 * HTTP-маркер «501 Not Implemented» (RFC&nbsp;9110, §15.6.2).
 *
 * <p>Сигнализирует, что запрошенная функциональность контракта API не поддерживается сервисом.
 * Является единственным HTTP-маркером, допустимым в пакете
 * {@link space.br1440.platform.errorhandling.exception.internal}:
 * платформенный {@link space.br1440.platform.errorhandling.exception.internal.ApiNotImplementedException}
 * фиксирует «контракт ещё не реализован» как внутренний сбой без семантической классификации.
 */
public interface NotImplementedException {
}
