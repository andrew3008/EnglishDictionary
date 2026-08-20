package space.br1440.platform.tracing.autoconfigure.propagation;

import java.util.Objects;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.runtime.propagation.control.OutboundApplicationIdentitySource;

final class TraceOperationsIdentitySource implements OutboundApplicationIdentitySource {

    private final TraceOperations traceOperations;

    TraceOperationsIdentitySource(TraceOperations traceOperations) {
        this.traceOperations = Objects.requireNonNull(traceOperations, "traceOperations");
    }

    @Override
    @Nullable
    public String currentRequestId() {
        return traceOperations.traceContext().requestId().orElse(null);
    }

    @Override
    @Nullable
    public String currentCorrelationId() {
        return traceOperations.traceContext().correlationId().orElse(null);
    }
}
