package space.br1440.platform.tracing.autoconfigure.integrations.async;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.otel.mdc.SpanContextMdcProjection;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class AsyncRequestIdentityMdcProjectionInterceptor implements MethodInterceptor {

    private static final IdentityValues EMPTY_IDENTITY = new IdentityValues(null, null);

    private final TraceOperations traceOperations;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        IdentityValues identity = currentIdentity();
        IdentityValues previousIdentity = currentMdcIdentity();

        SpanContextMdcProjection.Snapshot previousSpanMdc = SpanContextMdcProjection.installCurrent();
        try {
            writeIdentity(identity);
            return invocation.proceed();
        } finally {
            previousSpanMdc.restore();
            writeIdentity(previousIdentity);
        }
    }

    private IdentityValues currentIdentity() {
        try {
            ActiveTraceContextView context = traceOperations.traceContext();
            return new IdentityValues(
                    context.requestId().orElse(null),
                    context.correlationId().orElse(null));
        } catch (RuntimeException exception) {
            log.debug("Failed to read request identity for MDC projection", exception);
            return EMPTY_IDENTITY;
        }
    }

    private static IdentityValues currentMdcIdentity() {
        return new IdentityValues(
                MDC.get(TracingMdcKeys.REQUEST_ID),
                MDC.get(TracingMdcKeys.CORRELATION_ID)
        );
    }

    private static void writeIdentity(IdentityValues identity) {
        putOrRemove(TracingMdcKeys.REQUEST_ID, identity.requestId());
        putOrRemove(TracingMdcKeys.CORRELATION_ID, identity.correlationId());
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private record IdentityValues(String requestId, String correlationId) {
    }
}
