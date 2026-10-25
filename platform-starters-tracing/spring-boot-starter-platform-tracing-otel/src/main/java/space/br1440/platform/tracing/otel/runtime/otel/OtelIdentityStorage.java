package space.br1440.platform.tracing.otel.runtime.otel;

import java.util.Objects;
import java.util.Optional;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformBaggageKeys;
import space.br1440.platform.tracing.otel.mdc.IdentityMdcProjection;
import space.br1440.platform.tracing.otel.runtime.TraceControlBoundaryState;
import space.br1440.platform.tracing.runtime.propagation.CorrelationIdSupport;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

final class OtelIdentityStorage {

    private final ThreadLocal<TraceControlBoundaryState> traceControlBoundary = new ThreadLocal<>();

    Optional<String> requestId() {
        return IdentityMdcProjection.currentRequestId();
    }

    Optional<String> correlationId() {
        return IdentityMdcProjection.currentCorrelationId();
    }

    TraceControlBoundaryState traceControlBoundary() {
        TraceControlBoundaryState state = traceControlBoundary.get();
        return (state == null) ? TraceControlBoundaryState.EMPTY : state;
    }

    String requireCanonicalCorrelationId(String correlationId) {
        return CorrelationIdSupport.requireCanonical(correlationId);
    }

    CorrelationScope openCorrelationScope(String correlationId) {
        String canonical = CorrelationIdSupport.requireCanonical(correlationId);
        String currentRequestId = IdentityMdcProjection.currentRequestId().orElse(null);
        return open(new IdentityState(currentRequestId, canonical));
    }

    CorrelationScope openRequestScope(String requestId, boolean forceTrace, boolean qaTrace) {
        Objects.requireNonNull(requestId, "requestId");
        String currentCorrelationId = IdentityMdcProjection.currentCorrelationId().orElse(null);

        TraceControlBoundaryState previousBoundary = traceControlBoundary();
        traceControlBoundary.set(new TraceControlBoundaryState(forceTrace, qaTrace));
        Thread owner = Thread.currentThread();
        //noinspection resource — закрытие выполняет возвращаемый CorrelationScope
        CorrelationScope identityScope = open(new IdentityState(requestId, currentCorrelationId));

        return () -> {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("CorrelationScope must be closed by its owner thread");
            }

            try {
                identityScope.close();
            } finally {
                traceControlBoundary.set(previousBoundary);
            }
        };
    }

    private CorrelationScope open(IdentityState state) {
        Context context = IdentityMdcProjection.withIdentity(Context.current(), state.requestId(), state.correlationId());
        if (state.correlationId() != null) {
            Baggage baggage = Baggage.fromContext(context).toBuilder()
                    .put(PlatformBaggageKeys.CORRELATION_ID, state.correlationId())
                    .build();
            context = baggage.storeInContext(context);
        }

        Scope contextScope = context.makeCurrent();
        Object installedToken = IdentityMdcProjection.identityToken(Context.current());
        IdentityMdcProjection.Snapshot previousMdc = IdentityMdcProjection.installCurrent();

        return new IdentityScope(Thread.currentThread(), installedToken, contextScope, previousMdc);
    }

    private record IdentityState(String requestId, String correlationId) {
    }

    private static final class IdentityScope implements CorrelationScope {

        private final Thread owner;
        private final Object installedToken;
        private final Scope contextScope;
        private final IdentityMdcProjection.Snapshot previousMdc;
        private boolean closed;

        private IdentityScope(Thread owner,
                              Object installedToken,
                              Scope contextScope,
                              IdentityMdcProjection.Snapshot previousMdc) {
            this.owner = owner;
            this.installedToken = installedToken;
            this.contextScope = contextScope;
            this.previousMdc = previousMdc;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("CorrelationScope must be closed by its owner thread");
            }

            if (closed) {
                return;
            }

            if (IdentityMdcProjection.identityToken(Context.current()) != installedToken) {
                throw new IllegalStateException("CorrelationScope must be closed in LIFO order");
            }

            contextScope.close();
            closed = true;

            previousMdc.restore();
        }
    }
}
