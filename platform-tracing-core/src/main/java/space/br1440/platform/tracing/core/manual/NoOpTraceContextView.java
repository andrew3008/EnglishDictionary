package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.TraceContextView;

import java.util.Optional;

public final class NoOpTraceContextView implements TraceContextView {

    public static final NoOpTraceContextView INSTANCE = new NoOpTraceContextView();

    private NoOpTraceContextView() {
    }

    @Override
    @Nonnull
    public Optional<String> traceId() {
        return Optional.empty();
    }

    @Override
    @Nonnull
    public Optional<String> spanId() {
        return Optional.empty();
    }

    @Override
    @Nonnull
    public Optional<String> correlationId() {
        return Optional.empty();
    }
}
