package space.br1440.platform.tracing.api.propagation.control;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Порт формирования платформенных заголовков для исходящего вызова.
 *
 * <p>Transport adapter самостоятельно вычисляет {@link OutboundPropagationDecision} и текущий
 * {@link InboundTraceControl} (из явного app-plane boundary state — {@code RequestIdentityBoundarySupport}
 * на Servlet, Reactor {@code Context} на WebFlux) и записывает возвращённые заголовки в свой carrier.
 * Реализация порта — чистая функция от переданных параметров, без скрытого чтения текущего
 * execution context ({@code Context.current()}).</p>
 *
 * <p>Контракт сужен: резолвер использует из {@link InboundTraceControl} только
 * {@link InboundTraceControl#forceTrace()} и {@link InboundTraceControl#qaTrace()}.
 * {@link InboundTraceControl#requestId()} игнорируется — исходящий {@code requestId} владеет
 * application identity (см. {@code RequestIdentityBoundarySupport}), а не {@code InboundTraceControl}.
 * {@link InboundTraceControl#samplingReason()} и {@link InboundTraceControl#rawForceTraceValue()}
 * не влияют на исходящие заголовки.</p>
 */
public interface PlatformOutboundPropagation {

    /**
     * Формирует разрешённые платформенные заголовки для явно переданного состояния.
     *
     * @param decision решение transport policy; {@code null} эквивалентен запрету передачи
     * @param control  явно переданный app-plane trace-control boundary state; {@code null} эквивалентен
     *                 отсутствию {@code forceTrace}/{@code qaTrace}
     * @return immutable набор типизированных заголовков
     */
    @Nonnull
    OutboundPropagationHeaders resolve(@Nullable OutboundPropagationDecision decision, @Nullable InboundTraceControl control);
}
