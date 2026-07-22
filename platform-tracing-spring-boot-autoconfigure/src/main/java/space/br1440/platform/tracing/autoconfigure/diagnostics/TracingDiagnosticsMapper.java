package space.br1440.platform.tracing.autoconfigure.diagnostics;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.otel.runtime.state.TracingMode;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

import java.util.Objects;

/**
 * Maps internal {@link TracingState} to stable {@link TracingDiagnosticsView} (Slice 7).
 */
public final class TracingDiagnosticsMapper {

    private TracingDiagnosticsMapper() {
    }

    @Nonnull
    public static TracingDiagnosticsView fromState(@Nonnull TracingState state) {
        Objects.requireNonNull(state, "state");
        String mode = toPublicMode(state.mode());
        String reason = state.reason().orElse(null);
        return new TracingDiagnosticsView(mode, reason, state.details());
    }

    @Nonnull
    public static String toPublicMode(@Nonnull TracingMode internalMode) {
        Objects.requireNonNull(internalMode, "internalMode");
        return switch (internalMode) {
            case ENABLED -> "ENABLED";
            case DISABLED_BY_CONFIGURATION -> "DISABLED_BY_CONFIGURATION";
            case UNAVAILABLE -> "UNAVAILABLE";
            case NOOP -> "NOOP";
            case TEST -> "UNKNOWN";
        };
    }
}
