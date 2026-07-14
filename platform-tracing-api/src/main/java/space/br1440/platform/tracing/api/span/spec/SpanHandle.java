package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nullable;

/**
 * lifecycle-handle запущенного ручного span'а.
 */
public interface SpanHandle extends AutoCloseable {

    void recordException(@Nullable Throwable throwable);

    @Override
    void close();

}
