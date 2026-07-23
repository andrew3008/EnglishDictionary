package space.br1440.platform.tracing.autoconfigure.errorhandling;

import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;

/**
 * Поставщик снимка request/trace-контекста для платформенного error-handling.
 *
 * <p>Identity читается из того же read-only view, что и прикладной facade. MDC не является
 * источником данных. При полностью выключенной трассировке возвращается пустой снимок.
 */
public final class RequestTraceContextSnapshotSupplier implements Supplier<RequestTraceContextSnapshot> {

    private static final Logger log = LoggerFactory.getLogger(RequestTraceContextSnapshotSupplier.class);

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
            log.debug("Не удалось прочитать контекст при формировании RequestTraceContextSnapshot", exception);
            return emptySnapshot();
        }
    }

    private static RequestTraceContextSnapshot emptySnapshot() {
        return new RequestTraceContextSnapshot(null, null, null, null);
    }
}
