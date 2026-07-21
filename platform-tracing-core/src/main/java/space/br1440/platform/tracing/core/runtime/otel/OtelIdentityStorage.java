package space.br1440.platform.tracing.core.runtime.otel;

import java.util.Objects;
import java.util.Optional;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

/** Package-private OTel carrier для enabled runtime. */
final class OtelIdentityStorage {

    private static final String CORRELATION_BAGGAGE_KEY = "platform.correlation.id";
    private static final ContextKey<IdentityState> IDENTITY_KEY =
            ContextKey.named("platform-tracing-identity");

    Optional<String> requestId() {
        return Optional.ofNullable(current().requestId());
    }

    Optional<String> correlationId() {
        return Optional.ofNullable(current().correlationId());
    }

    String requireCanonicalCorrelationId(String correlationId) {
        return requireCanonical(correlationId);
    }

    CorrelationScope openCorrelationScope(String correlationId) {
        String canonical = requireCanonical(correlationId);
        IdentityState current = current();
        return open(new IdentityState(current.requestId(), canonical));
    }

    private static String requireCanonical(String correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        if (correlationId.isEmpty() || correlationId.length() > 128) {
            throw new IllegalArgumentException("correlationId length must be between 1 and 128");
        }
        for (int index = 0; index < correlationId.length(); index++) {
            char character = correlationId.charAt(index);
            boolean canonical = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-';
            if (!canonical) {
                throw new IllegalArgumentException("correlationId must match [A-Za-z0-9_-]{1,128}");
            }
        }
        return correlationId;
    }

    CorrelationScope openRequestScope(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        IdentityState current = current();
        return open(new IdentityState(requestId, current.correlationId()));
    }

    private CorrelationScope open(IdentityState state) {
        String previousRequestId = MDC.get(TracingMdcKeys.REQUEST_ID);
        String previousCorrelationId = MDC.get(TracingMdcKeys.CORRELATION_ID);
        Context context = Context.current().with(IDENTITY_KEY, state);
        if (state.correlationId() != null) {
            Baggage baggage = Baggage.fromContext(context).toBuilder()
                    .put(CORRELATION_BAGGAGE_KEY, state.correlationId())
                    .build();
            context = baggage.storeInContext(context);
        }
        Scope contextScope = context.makeCurrent();
        putOrRemove(TracingMdcKeys.REQUEST_ID, state.requestId());
        putOrRemove(TracingMdcKeys.CORRELATION_ID, state.correlationId());
        return new IdentityScope(
                Thread.currentThread(),
                state,
                contextScope,
                previousRequestId,
                previousCorrelationId);
    }

    private static IdentityState current() {
        IdentityState state = Context.current().get(IDENTITY_KEY);
        return state == null ? IdentityState.EMPTY : state;
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private record IdentityState(String requestId, String correlationId) {

        private static final IdentityState EMPTY = new IdentityState(null, null);
    }

    private static final class IdentityScope implements CorrelationScope {

        private final Thread owner;
        private final IdentityState installedState;
        private final Scope contextScope;
        private final String previousRequestId;
        private final String previousCorrelationId;
        private boolean closed;

        private IdentityScope(Thread owner,
                              IdentityState installedState,
                              Scope contextScope,
                              String previousRequestId,
                              String previousCorrelationId) {
            this.owner = owner;
            this.installedState = installedState;
            this.contextScope = contextScope;
            this.previousRequestId = previousRequestId;
            this.previousCorrelationId = previousCorrelationId;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("CorrelationScope must be closed by its owner thread");
            }
            if (closed) {
                return;
            }
            if (Context.current().get(IDENTITY_KEY) != installedState) {
                throw new IllegalStateException("CorrelationScope must be closed in LIFO order");
            }
            contextScope.close();
            closed = true;
            putOrRemove(TracingMdcKeys.REQUEST_ID, previousRequestId);
            putOrRemove(TracingMdcKeys.CORRELATION_ID, previousCorrelationId);
        }
    }
}
