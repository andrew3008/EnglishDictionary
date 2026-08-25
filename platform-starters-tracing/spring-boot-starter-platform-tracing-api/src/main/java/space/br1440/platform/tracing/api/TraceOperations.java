package space.br1440.platform.tracing.api;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

/**
 * Прикладной контракт доступа к подсистеме трассировки текущего запроса.
 *
 * <p>Предоставляет:
 * <ul>
 *   <li>{@link #traceContext()} — read-only представление активного trace-контекста
 *       для корреляции, логирования и передачи trace-id в downstream-системы;</li>
 *   <li>{@link #spans()} — фабрика span'ов для ручного instrumentation прикладного кода.</li>
 * </ul>
 *
 * <p>Не является рантаймом трассировки, SDK, SPI, propagation-подсистемой
 * или конфигурационным объектом. Реализация предоставляется платформой через DI-контейнер.
 */
public interface TraceOperations {

    @Nonnull
    ActiveTraceContextView traceContext();

    @Nonnull
    SpanFactory spans();

    @Nonnull
    CorrelationScope openCorrelationScope(@Nonnull String correlationId);

    void withCorrelationId(@Nonnull String correlationId, @Nonnull Runnable action);

    <T> T withCorrelationId(@Nonnull String correlationId,
                            @Nonnull ThrowingSupplier<T> action) throws Exception;

}
