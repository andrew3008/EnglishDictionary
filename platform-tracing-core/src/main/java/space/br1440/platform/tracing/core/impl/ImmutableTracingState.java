package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ImmutableTracingState implements TracingState {

    private static final TracingState ENABLED = new ImmutableTracingState(
            TracingMode.ENABLED, Optional.empty(), Map.of());

    private final TracingMode mode;
    private final Optional<String> reason;
    private final Map<String, String> details;

    private ImmutableTracingState(@Nonnull TracingMode mode,
                                  @Nonnull Optional<String> reason,
                                  @Nonnull Map<String, String> details) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.details = Map.copyOf(details);
    }

    static TracingState enabled() {
        return ENABLED;
    }

    static TracingState of(@Nonnull TracingMode mode,
                           @Nonnull Optional<String> reason,
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
        return reason;
    }

    @Override
    @Nonnull
    public Map<String, String> details() {
        return details;
    }
}
