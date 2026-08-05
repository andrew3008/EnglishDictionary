package space.br1440.platform.tracing.autoconfigure.integrations.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

class AsyncRequestIdentityMdcProjectionInterceptorTest {

    private static final String UNRELATED_MDC_KEY = "tenant.id";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void projectsAuthoritativeIdentityAndRestoresPreviousMdc() throws Throwable {
        AsyncRequestIdentityMdcProjectionInterceptor interceptor = interceptor("request-42", "correlation-42");
        MethodInvocation invocation = mock(MethodInvocation.class);
        MDC.put(TracingMdcKeys.REQUEST_ID, "previous-request");
        MDC.put(TracingMdcKeys.CORRELATION_ID, "previous-correlation");
        MDC.put(UNRELATED_MDC_KEY, "tenant-42");
        when(invocation.proceed()).thenAnswer(ignored -> {
            assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("request-42");
            assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isEqualTo("correlation-42");
            assertThat(MDC.get(UNRELATED_MDC_KEY)).isEqualTo("tenant-42");
            return "result";
        });

        assertThat(interceptor.invoke(invocation)).isEqualTo("result");
        assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("previous-request");
        assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isEqualTo("previous-correlation");
        assertThat(MDC.get(UNRELATED_MDC_KEY)).isEqualTo("tenant-42");
    }

    @Test
    void clearsStaleWorkerProjectionWhenCurrentIdentityIsEmpty() throws Throwable {
        AsyncRequestIdentityMdcProjectionInterceptor interceptor = interceptor(null, null);
        MethodInvocation invocation = mock(MethodInvocation.class);
        MDC.put(TracingMdcKeys.REQUEST_ID, "stale-request");
        MDC.put(TracingMdcKeys.CORRELATION_ID, "stale-correlation");
        when(invocation.proceed()).thenAnswer(ignored -> {
            assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isNull();
            assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isNull();
            return null;
        });

        interceptor.invoke(invocation);

        assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("stale-request");
        assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isEqualTo("stale-correlation");
    }

    @Test
    void restoresMdcWhenAsyncMethodFails() throws Throwable {
        AsyncRequestIdentityMdcProjectionInterceptor interceptor = interceptor("request-failure", "correlation-failure");
        MethodInvocation invocation = mock(MethodInvocation.class);
        MDC.put(TracingMdcKeys.REQUEST_ID, "previous-request");
        when(invocation.proceed()).thenThrow(new IllegalStateException("expected"));

        assertThatThrownBy(() -> interceptor.invoke(invocation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected");
        assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("previous-request");
        assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isNull();
    }

    @Test
    void clearsStaleWorkerProjectionWhenIdentityCannotBeRead() throws Throwable {
        TraceOperations traceOperations = mock(TraceOperations.class);
        when(traceOperations.traceContext()).thenThrow(new IllegalStateException("unavailable"));
        AsyncRequestIdentityMdcProjectionInterceptor interceptor =
                new AsyncRequestIdentityMdcProjectionInterceptor(traceOperations);
        MethodInvocation invocation = mock(MethodInvocation.class);
        MDC.put(TracingMdcKeys.REQUEST_ID, "stale-request");
        MDC.put(TracingMdcKeys.CORRELATION_ID, "stale-correlation");
        when(invocation.proceed()).thenAnswer(ignored -> {
            assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isNull();
            assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isNull();
            return "result";
        });

        assertThat(interceptor.invoke(invocation)).isEqualTo("result");
        assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("stale-request");
        assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isEqualTo("stale-correlation");
    }

    private static AsyncRequestIdentityMdcProjectionInterceptor interceptor(String requestId, String correlationId) {
        ActiveTraceContextView context = mock(ActiveTraceContextView.class);
        when(context.requestId()).thenReturn(Optional.ofNullable(requestId));
        when(context.correlationId()).thenReturn(Optional.ofNullable(correlationId));
        TraceOperations traceOperations = mock(TraceOperations.class);
        when(traceOperations.traceContext()).thenReturn(context);
        return new AsyncRequestIdentityMdcProjectionInterceptor(traceOperations);
    }
}
