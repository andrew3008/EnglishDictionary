package space.br1440.platform.tracing.api.propagation.control;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Порт формирования платформенных заголовков для исходящего вызова.
 *
 * <p>Transport adapter самостоятельно вычисляет {@link OutboundPropagationDecision} и записывает
 * возвращённые заголовки в свой carrier. Реализация порта читает текущий execution context и
 * возвращает пустой результат при отсутствии разрешённого платформенного состояния.</p>
 */
public interface PlatformOutboundPropagation {

    /**
     * Формирует разрешённые платформенные заголовки для текущего execution context.
     *
     * @param decision решение transport policy; {@code null} эквивалентен запрету передачи
     * @return immutable набор типизированных заголовков
     */
    @Nonnull
    OutboundPropagationHeaders resolve(@Nullable OutboundPropagationDecision decision);
}
