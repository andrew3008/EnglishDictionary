package space.br1440.platform.tracing.api;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.manual.ActiveTraceContextView;
import space.br1440.platform.tracing.api.manual.ManualTracing;

/**
 * Прикладной контракт операций над активным trace-контекстом текущего запроса.
 *
 * <p>Предоставляет две возможности:
 * <ul>
 *   <li>{@link #traceContext()} - read-only представление активного trace-контекста
 *       для корреляции, логирования и передачи trace-id в downstream-системы;</li>
 *   <li>{@link #manual()} - вход в API ручного создания и обогащения span'ов.</li>
 * </ul>
 *
 * <p>Не является рантаймом трассировки, SDK, SPI, propagation-подсистемой
 * или конфигурационным объектом. Реализация предоставляется платформой
 * через DI-контейнер.
 *
 * <p>Прикладной код обычно внедряет этот интерфейс как dependency и не должен
 * реализовывать его напрямую, кроме тестовых стабов.
 *
 * @see ManualTracing
 * @see ActiveTraceContextView
 */
public interface TraceOperations {

    @Nonnull
    ActiveTraceContextView traceContext();

    @Nonnull
    ManualTracing manual();

}
