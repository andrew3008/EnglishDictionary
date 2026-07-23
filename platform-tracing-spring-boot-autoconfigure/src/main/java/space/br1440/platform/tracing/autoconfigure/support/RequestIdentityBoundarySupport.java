package space.br1440.platform.tracing.autoconfigure.support;

import java.util.Objects;
import java.util.Optional;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

/**
 * Единственная application-plane граница привязки request identity инфраструктурными адаптерами.
 * Прикладной API не получает возможности изменять requestId.
 */
public final class RequestIdentityBoundarySupport {

    private final TracingRuntime tracingRuntime;

    public RequestIdentityBoundarySupport(TracingRuntime tracingRuntime) {
        this.tracingRuntime = Objects.requireNonNull(tracingRuntime, "tracingRuntime");
    }

    public CorrelationScope openRequestScope(String requestId) {
        return tracingRuntime.openRequestIdentityScope(requestId);
    }

    public CorrelationScope openCorrelationScope(String correlationId) {
        return tracingRuntime.openCorrelationScope(correlationId);
    }

    public String requireCanonicalCorrelationId(String correlationId) {
        return tracingRuntime.requireCanonicalCorrelationId(correlationId);
    }

    public Optional<String> correlationId() {
        return tracingRuntime.currentCorrelationId();
    }

    public Optional<String> requestId() {
        return tracingRuntime.currentRequestId();
    }
}
