package space.br1440.platform.tracing.otel.mdc;

import java.util.Optional;

import org.slf4j.MDC;

import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class IdentityMdcProjection {

    private static final ContextKey<Identity> IDENTITY_KEY = ContextKey.named("platform-tracing-identity");

    public static Context withIdentity(Context context, String requestId, String correlationId) {
        return context.with(IDENTITY_KEY, new Identity(requestId, correlationId));
    }

    public static Object identityToken(Context context) {
        return context.get(IDENTITY_KEY);
    }

    public static Optional<String> currentRequestId() {
        return Optional.ofNullable(current().requestId());
    }

    public static Optional<String> currentCorrelationId() {
        return Optional.ofNullable(current().correlationId());
    }

    public static Snapshot installCurrent() {
        SpanContextMdcProjection.Snapshot previousSpanMdc = SpanContextMdcProjection.installCurrent();
        String previousRequestId = MDC.get(TracingMdcKeys.REQUEST_ID);
        String previousCorrelationId = MDC.get(TracingMdcKeys.CORRELATION_ID);

        Identity identity = current();
        putOrRemove(TracingMdcKeys.REQUEST_ID, identity.requestId());
        putOrRemove(TracingMdcKeys.CORRELATION_ID, identity.correlationId());

        return new Snapshot(previousSpanMdc, previousRequestId, previousCorrelationId);
    }

    private static Identity current() {
        Identity identity = Context.current().get(IDENTITY_KEY);
        return (identity == null) ? Identity.EMPTY : identity;
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private record Identity(String requestId, String correlationId) {

        private static final Identity EMPTY = new Identity(null, null);
    }

    public record Snapshot(SpanContextMdcProjection.Snapshot spanSnapshot, String requestId, String correlationId) {

        public void restore() {
            putOrRemove(TracingMdcKeys.REQUEST_ID, requestId);
            putOrRemove(TracingMdcKeys.CORRELATION_ID, correlationId);
            spanSnapshot.restore();
        }
    }
}
