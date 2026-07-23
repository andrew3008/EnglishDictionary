package space.br1440.platform.tracing.otel.context;

import java.util.Optional;
import java.util.function.Supplier;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.context.ActiveTraceContextView;

public final class DefaultActiveTraceContextView implements ActiveTraceContextView {

    private final Supplier<Optional<String>> traceIdSupplier;
    private final Supplier<Optional<String>> spanIdSupplier;
    private final Supplier<Optional<String>> requestIdSupplier;
    private final Supplier<Optional<String>> correlationIdSupplier;

    public DefaultActiveTraceContextView(@Nonnull Supplier<Optional<String>> traceIdSupplier,
                                         @Nonnull Supplier<Optional<String>> spanIdSupplier,
                                         @Nonnull Supplier<Optional<String>> requestIdSupplier,
                                         @Nonnull Supplier<Optional<String>> correlationIdSupplier) {
        this.traceIdSupplier = traceIdSupplier;
        this.spanIdSupplier = spanIdSupplier;
        this.requestIdSupplier = requestIdSupplier;
        this.correlationIdSupplier = correlationIdSupplier;
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
    public Optional<String> requestId() {
        return requestIdSupplier.get();
    }

    @Override
    @Nonnull
    public Optional<String> correlationId() {
        return correlationIdSupplier.get();
    }
}
