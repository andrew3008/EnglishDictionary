package space.br1440.platform.tracing.core.runtime;

import jakarta.annotation.Nonnull;

/**
 * Marker for {@link TracingRuntime} decorators that fully delegate behavior.
 */
public interface DelegatingTracingRuntime extends TracingRuntime {

    @Nonnull
    TracingRuntime delegate();
}
