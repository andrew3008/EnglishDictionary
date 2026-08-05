package space.br1440.platform.tracing.autoconfigure.integrations.async;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

final class AsyncRequestIdentityMdcProjectionInterceptor implements MethodInterceptor {

    private static final IdentityValues EMPTY_IDENTITY = new IdentityValues(null, null);

    private final TraceOperations traceOperations;

    AsyncRequestIdentityMdcProjectionInterceptor(TraceOperations traceOperations) {
        this.traceOperations = traceOperations;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        IdentityValues identity = currentIdentity();
        String previousRequestId = MDC.get(TracingMdcKeys.REQUEST_ID);
        String previousCorrelationId = MDC.get(TracingMdcKeys.CORRELATION_ID);

        putOrRemove(TracingMdcKeys.REQUEST_ID, identity.requestId());
        putOrRemove(TracingMdcKeys.CORRELATION_ID, identity.correlationId());

        try {
            return invocation.proceed();
        } finally {
            putOrRemove(TracingMdcKeys.REQUEST_ID, previousRequestId);
            putOrRemove(TracingMdcKeys.CORRELATION_ID, previousCorrelationId);
        }
    }

    private IdentityValues currentIdentity() {
        try {
            ActiveTraceContextView context = traceOperations.traceContext();
            return new IdentityValues(context.requestId().orElse(null), context.correlationId().orElse(null));
        } catch (RuntimeException ignored) {
            return EMPTY_IDENTITY;
        }
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
