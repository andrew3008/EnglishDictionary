package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

import java.util.Optional;

/**
 * Представление активного trace/span-контекста только для чтения — для корреляции, логирования и моделей ошибок.
 */
public interface ActiveTraceContextView {

    @Nonnull
    Optional<String> traceId();

    @Nonnull
    Optional<String> spanId();

    @Nonnull
    Optional<String> requestId();

    @Nonnull
    Optional<String> correlationId();

}
