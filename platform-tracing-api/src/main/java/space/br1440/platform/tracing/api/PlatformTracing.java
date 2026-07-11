package space.br1440.platform.tracing.api;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;

/**
 * Публичный фасад платформенной ручной трассировки.
 * <p>
 * Прикладной код получает контекст только для чтения через {@link #traceContext()} и
 * создаёт span'ы через {@link #manual()}.
 */
public interface PlatformTracing {

    @Nonnull
    TraceContextView traceContext();

    @Nonnull
    ManualTracing manual();

}
