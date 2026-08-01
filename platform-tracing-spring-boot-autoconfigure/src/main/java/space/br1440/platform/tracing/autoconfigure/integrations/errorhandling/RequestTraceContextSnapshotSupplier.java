package space.br1440.platform.tracing.autoconfigure.integrations.errorhandling;

import java.util.Objects;
import java.util.function.Supplier;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class RequestTraceContextSnapshotSupplier implements Supplier<RequestTraceContextSnapshot> {

    private final Supplier<TraceOperations> traceOperationsSupplier;

    public RequestTraceContextSnapshotSupplier(Supplier<TraceOperations> traceOperationsSupplier) {
        this.traceOperationsSupplier = Objects.requireNonNull(traceOperationsSupplier, "traceOperationsSupplier");
    }

    @Override
    public RequestTraceContextSnapshot get() {
        try {
            TraceOperations traceOperations = traceOperationsSupplier.get();
            if (traceOperations == null) {
                return emptySnapshot();
            }

            ActiveTraceContextView context = traceOperations.traceContext();
            return new RequestTraceContextSnapshot(
                    context.requestId().orElse(null),
                    context.correlationId().orElse(null),
                    context.traceId().orElse(null),
                    context.spanId().orElse(null));
        } catch (RuntimeException exception) {
            log.debug("Failed to read context while building RequestTraceContextSnapshot", exception);
            return emptySnapshot();
        }
    }

    private static RequestTraceContextSnapshot emptySnapshot() {
        return new RequestTraceContextSnapshot(null, null, null, null);
    }
}
