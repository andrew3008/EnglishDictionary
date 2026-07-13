package space.br1440.platform.tracing.api.span;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.manual.OperationSpanBuilder;
import space.br1440.platform.tracing.api.manual.TransportTracing;
import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;

/**
 * Фабрика span'ов для ручного instrumentation прикладного кода.
 *
 * <p>Создаёт span-объекты трёх категорий:
 * <ul>
 *   <li>{@link #operation(String)} — span для прикладной или бизнес-операции;</li>
 *   <li>{@link #transport()} — span'ы транспортного уровня: HTTP, RPC, Kafka, Database;</li>
 *   <li>{@link #fromSpec(SpanSpec)} — span по декларативной спецификации.</li>
 * </ul>
 *
 * <p>Не является runtime, SDK, sampler, exporter или propagation API.
 * Получается через {@link space.br1440.platform.tracing.api.TraceOperations#spans()}.
 */
public interface SpanFactory {

    /**
     * Создаёт builder span'а для прикладной или бизнес-операции.
     *
     * <p>Используется для ручного instrumentation единиц работы, не охватываемых
     * авто-инструментированием платформы: внутренние вычисления, batch-шаги,
     * доменные транзакции, scheduled-задачи и аналогичные прикладные операции.
     *
     * <p>Пример:
     * <pre>{@code
     * traceOperations.spans()
     *     .operation("portfolio.recalculate")
     *     .start()
     *     .scoped(() -> recalculate(request));
     * }</pre>
     *
     * <p>Соглашение по именованию: {@code domain.verb} в нижнем регистре
     * (например, {@code "payment.process"}, {@code "cache.invalidate"},
     * {@code "report.generate"}). Не должно содержать пользовательских данных,
     * trace-id, span-id или иных runtime-значений.
     *
     * @param name логическое имя операции
     * @return builder для настройки и старта span'а
     */
    @Nonnull
    OperationSpanBuilder operation(@Nonnull String name);

    /**
     * Возвращает sub-factory транспортных span'ов: HTTP, RPC, Kafka, Database.
     *
     * @return sub-factory транспортных span'ов
     */
    @Nonnull
    TransportTracing transport();

    /**
     * Создаёт execution-обёртку span'а по декларативной спецификации.
     *
     * @param spec спецификация span'а
     * @return execution-обёртка для запуска span'а
     */
    @Nonnull
    SpanExecution fromSpec(@Nonnull SpanSpec spec);

}
