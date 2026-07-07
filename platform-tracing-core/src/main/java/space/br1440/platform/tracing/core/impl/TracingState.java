package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;

import java.util.Map;
import java.util.Optional;

/**
 * Internal supportability state for {@link TracingImplementation} (Slice 2).
 */
public interface TracingState {

    @Nonnull
    TracingMode mode();

    @Nonnull
    Optional<String> reason();

    @Nonnull
    Map<String, String> details();
}
