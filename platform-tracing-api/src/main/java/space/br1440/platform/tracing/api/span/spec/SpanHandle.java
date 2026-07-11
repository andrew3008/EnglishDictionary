package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nullable;

/**
 * Минимальный lifecycle-handle запущенного ручного span'а.
 * <p>
 * Отличается от legacy {@link space.br1440.platform.tracing.api.span.SpanScope}.
 */
public interface SpanHandle extends AutoCloseable {

    void recordException(@Nullable Throwable throwable);

    @Override
    void close();

}
