package space.br1440.platform.tracing.core.context;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.ActiveTraceContextView;

import java.util.Optional;
import java.util.function.Supplier;

public final class DefaultActiveTraceContextView implements ActiveTraceContextView {

    private final Supplier<Optional<String>> traceIdSupplier;
    private final Supplier<Optional<String>> spanIdSupplier;

    public DefaultActiveTraceContextView(@Nonnull Supplier<Optional<String>> traceIdSupplier,
                                   @Nonnull Supplier<Optional<String>> spanIdSupplier) {
        this.traceIdSupplier = traceIdSupplier;
        this.spanIdSupplier = spanIdSupplier;
    }

    @Override
    @Nonnull
    public Optional<String> traceId() {
        return traceIdSupplier.get();
    }

    @Override
    @Nonnull
    public Optional<String> spanId() {
        return spanIdSupplier.get();
    }

    @Override
    @Nonnull
    public Optional<String> correlationId() {
        return Optional.empty();
    }
}
