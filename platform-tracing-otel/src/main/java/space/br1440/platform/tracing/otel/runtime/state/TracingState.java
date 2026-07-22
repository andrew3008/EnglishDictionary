package space.br1440.platform.tracing.otel.runtime.state;

import jakarta.annotation.Nonnull;

import java.util.Map;
import java.util.Optional;

public interface TracingState {

    @Nonnull
    TracingMode mode();

    @Nonnull
    Optional<String> reason();

    @Nonnull
    Map<String, String> details();

}
