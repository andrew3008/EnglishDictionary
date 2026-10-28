package space.br1440.platform.tracing.autoconfigure.identity;

import java.util.Objects;
import java.util.Optional;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

public final class RequestIdentityBoundarySupport {

    private final TracingRuntime tracingRuntime;

    public RequestIdentityBoundarySupport(TracingRuntime tracingRuntime) {
        this.tracingRuntime = Objects.requireNonNull(tracingRuntime, "tracingRuntime");
    }

    public CorrelationScope openRequestScope(String requestId) {
        return tracingRuntime.openRequestIdentityScope(requestId);
    }

    public CorrelationScope openRequestScope(String requestId, boolean forceTrace, boolean qaTrace) {
        return tracingRuntime.openRequestIdentityScope(requestId, forceTrace, qaTrace);
    }

    public boolean forceTrace() {
        return tracingRuntime.currentTraceControlBoundary().forceTrace();
    }

    public boolean qaTrace() {
        return tracingRuntime.currentTraceControlBoundary().qaTrace();
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
