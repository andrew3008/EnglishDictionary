package space.br1440.platform.tracing.core.runtime.state;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ImmutableTracingState implements TracingState {

    private static final TracingState ENABLED = new ImmutableTracingState(
            TracingMode.ENABLED, null, Map.of()
    );

    private final TracingMode mode;
    private final String reason;
    private final Map<String, String> details;

    private ImmutableTracingState(@Nonnull TracingMode mode,
                                  @Nullable String reason,
                                  @Nonnull Map<String, String> details) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.reason = reason;
        this.details = Map.copyOf(details);
    }

    public static TracingState enabled() {
        return ENABLED;
    }

    public static TracingState of(@Nonnull TracingMode mode,
                                  @Nullable String reason,
                                  @Nonnull Map<String, String> details) {
        return new ImmutableTracingState(mode, reason, details);
    }

    @Override
    @Nonnull
    public TracingMode mode() {
        return mode;
    }

    @Override
    @Nonnull
    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    @Override
    @Nonnull
    public Map<String, String> details() {
        return details;
    }
}