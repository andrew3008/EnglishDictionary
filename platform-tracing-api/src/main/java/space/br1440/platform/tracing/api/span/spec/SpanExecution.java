package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.function.Supplier;

/**
 * Точка запуска span'а, собранного через {@link SpanSpec}.
 * <p>
 * Создаётся исключительно через {@code spans().fromSpec(spec)}.
 */
public interface SpanExecution {

    /**
     * Открывает span и возвращает handle для явного управления его жизненным циклом.
     * <p>
     * Вызывающий код обязан закрыть {@link SpanHandle} в любом исходе выполнения.
     * Предпочтительный паттерн:
     *
     * <pre>{@code
     * try (SpanHandle handle = execution.start()) {
     *     // ...
     * } catch (Exception e) {
     *     handle.recordException(e);
     *     throw e;
     * }
     * }</pre>
     *
     * @return handle открытого span'а; никогда не {@code null}
     */
    @Nonnull
    SpanHandle start();

    /**
     * Открывает span, выполняет {@code action} и закрывает span.
     * <p>
     * Если {@code action} бросает исключение — оно фиксируется на span'е,
     * span закрывается, исключение пробрасывается без изменений.
     *
     * @param action выполняемое действие; не {@code null}
     */
    void run(@Nonnull Runnable action);

    /**
     * Открывает span, выполняет {@code supplier}, закрывает span и возвращает результат.
     * <p>
     * Если {@code supplier} бросает {@link RuntimeException} — она фиксируется на span'е,
     * span закрывается, исключение пробрасывается без изменений.
     */
    @Nonnull
    <T> T call(@Nonnull Supplier<T> supplier);

    /**
     * То же, что {@link #call(Supplier)}, но допускает supplier с checked-исключением.
     *
     * <p>Если {@code supplier} бросает исключение — оно фиксируется на span'е,
     * span закрывается, исключение пробрасывается без оборачивания.
     *
     * @param supplier поставщик результата; не {@code null}
     * @param <T>      тип возвращаемого значения
     * @return результат {@code supplier}; не {@code null}
     * @throws Exception если {@code supplier} бросает исключение
     */
    @Nonnull
    <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception;

}
