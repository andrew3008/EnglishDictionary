package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

import java.util.Optional;

/**
 * Представление активного trace/span-контекста только для чтения — для корреляции, логирования и моделей ошибок.
 */
public interface TraceContextView {

    @Nonnull
    Optional<String> traceId();

    @Nonnull
    Optional<String> spanId();

    @Nonnull
    Optional<String> correlationId();

}
