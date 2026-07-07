package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Minimal lifecycle handle for a started manual span (v3 API).
 * <p>
 * Distinct from legacy {@link space.br1440.platform.tracing.api.span.SpanScope}.
 */
public interface SpanHandle extends AutoCloseable {

    void recordException(@Nullable Throwable throwable);

    @Override
    void close();

}
