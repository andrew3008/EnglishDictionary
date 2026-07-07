package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

import java.util.Optional;

/**
 * Read-only view of the active trace/span context for correlation, logging, and error models.
 * <p>
 * Does not expose OpenTelemetry {@code Context}, {@code Span}, or {@code SpanContext}.
 */
public interface TraceContextView {

    @Nonnull
    Optional<String> traceId();

    @Nonnull
    Optional<String> spanId();

    @Nonnull
    Optional<String> correlationId();

}
