package space.br1440.platform.tracing.api.span;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Управляющий хэндл активного span'а.
 * <p>
 * Возвращается типизированными методами {@link space.br1440.platform.tracing.api.PlatformTracing}
 * и должен использоваться в блоке try-with-resources, чтобы гарантировать корректное завершение
 * span'а вне зависимости от исключений.
 *
 * <pre>{@code
 * try (SpanScope scope = platformTracing.startInternal("checkout")) {
 *     scope.setAttribute("order.id", orderId);
 *     try {
 *         // бизнес-логика
 *         scope.setResult(SpanResult.SUCCESS);
 *     } catch (Exception e) {
 *         scope.recordException(e); // recordException автоматически выставит FAILURE
 *         throw e;
 *     }
 * }
 * }</pre>
 *
 * @apiNote Незакрытый {@link SpanScope} приводит к утечке ThreadLocal-контекста и порче
 *          последующих корреляций trace на этом потоке. Использование try-with-resources
 *          обязательно — не оборачивайте {@code startSpan(...)} в простую переменную без
 *          {@code try}-блока.
 */
public interface SpanScope extends AutoCloseable {

    @Nonnull
    SpanScope setAttribute(@Nonnull String key, @Nullable String value);

    @Nonnull
    SpanScope setAttribute(@Nonnull String key, long value);

    @Nonnull
    SpanScope setAttribute(@Nonnull String key, double value);

    @Nonnull
    SpanScope setAttribute(@Nonnull String key, boolean value);

    @Nonnull
    SpanScope addEvent(@Nonnull String name);

    /**
     * Устанавливает финальный статус span'а.
     * Метод можно вызывать многократно; учитывается последнее значение перед {@link #close()}.
     */
    @Nonnull
    SpanScope setResult(@Nonnull SpanResult result);

    /**
     * Регистрирует исключение на текущем span'е и автоматически выставляет {@link SpanResult#FAILURE}.
     */
    @Nonnull
    SpanScope recordException(@Nullable Throwable throwable);

    /**
     * Завершает span. Метод идемпотентен: повторный вызов является no-op.
     */
    @Override
    void close();

}
