package space.br1440.platform.tracing.api;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.manual.ActiveTraceContextView;
import space.br1440.platform.tracing.api.span.SpanFactory;

/**
 * Прикладной контракт доступа к подсистеме трассировки текущего запроса.
 *
 * <p>Предоставляет два capability:
 * <ul>
 *   <li>{@link #traceContext()} — read-only представление активного trace-контекста
 *       для корреляции, логирования и передачи trace-id в downstream-системы;</li>
 *   <li>{@link #spans()} — фабрика span'ов для ручного instrumentation прикладного кода.</li>
 * </ul>
 *
 * <p>Не является рантаймом трассировки, SDK, SPI, propagation-подсистемой
 * или конфигурационным объектом. Реализация предоставляется платформой
 * через DI-контейнер.
 */
public interface TraceOperations {

    @Nonnull
    ActiveTraceContextView traceContext();

    @Nonnull
    SpanFactory spans();

}
