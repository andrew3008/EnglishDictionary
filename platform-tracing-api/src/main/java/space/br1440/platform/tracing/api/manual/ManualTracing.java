package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanExecution;

/**
 * Точка входа в управляемую платформой ручную трассировку ({@code traceOperations.manual()} в v3 cutover).
 */
public interface ManualTracing {

    @Nonnull
    OperationSpanBuilder operation(@Nonnull String name);

    @Nonnull
    TransportTracing transport();

    @Nonnull
    SpanExecution spanFromSpec(@Nonnull SpanSpec spec);

}
