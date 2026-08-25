package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nullable;

/**
 * lifecycle-handle запущенного ручного span'а.
 * <p>
 * Handle активирует span в текущем execution context. Его нельзя передавать между потоками:
 * {@link #close()} должен быть вызван в том же потоке, в котором handle был получен. Для async и
 * reactive boundaries следует использовать поддерживаемый механизм propagation, а не переносить
 * открытый handle.
 */
public interface SpanHandle extends AutoCloseable {

    void recordException(@Nullable Throwable throwable);

    @Override
    void close();

}
