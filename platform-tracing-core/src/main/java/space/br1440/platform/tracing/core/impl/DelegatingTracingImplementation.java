package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;

/**
 * Marker for {@link TracingImplementation} decorators that fully delegate behavior.
 */
public interface DelegatingTracingImplementation extends TracingImplementation {

    @Nonnull
    TracingImplementation delegate();
}
