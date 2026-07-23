package space.br1440.platform.tracing.autoconfigure.servlet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceMdc;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceNameResolver;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceResponseHeaderServletFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private TraceOperations traceOperations;
    private ActiveTraceContextView traceContextView;
    private TracingProperties properties;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private TraceResponseHeaderServletFilter filter;
    private TracingRuntime runtime;

    @BeforeEach
    void setUp() {
        MDC.clear();
        traceOperations = mock(TraceOperations.class);
        traceContextView = mock(ActiveTraceContextView.class);
        when(traceOperations.traceContext()).thenReturn(traceContextView);
        properties = new TracingProperties();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        runtime = NoOpTracingRuntime.noop();
        filter = new TraceResponseHeaderServletFilter(
                traceOperations,
                properties,
                new RequestIdentityBoundarySupport(runtime)
        );
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void capturesTraceIdBeforeChainAndWritesAfter() throws ServletException, java.io.IOException {
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-incoming-1");
        when(traceContextView.traceId())
                .thenReturn(Optional.of("trace-abc-123"))
                .thenReturn(Optional.empty());
        when(response.isCommitted()).thenReturn(false);

        AtomicReference<Optional<String>> seenInsideChain = new AtomicReference<>();
        doAnswer(invocation -> {
            seenInsideChain.set(traceContextView.traceId());
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        InOrder order = inOrder(chain, response);
        order.verify(chain).doFilter(request, response);
        order.verify(response).setHeader(eq(properties.getResponse().getHeaderName()), eq("req-incoming-1"));
        order.verify(response).setHeader(eq(PlatformHeaders.X_TRACE_ID), eq("trace-abc-123"));
        assertThat(seenInsideChain.get()).isEmpty();
    }

    @Test
    void correlationIdWrittenButNoTraceIdWhenTraceAbsent() throws ServletException, java.io.IOException {
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-2");
        when(traceContextView.traceId()).thenReturn(Optional.empty());
        when(response.isCommitted()).thenReturn(false);

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq(PlatformHeaders.X_TRACE_ID), anyString());
        verify(response, times(1)).setHeader(eq(properties.getResponse().getHeaderName()), eq("req-2"));
    }

    @Test
    void bindsRequestIdOnlyForListenerExecution() throws ServletException, java.io.IOException {
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("request-42");
        when(traceContextView.traceId()).thenReturn(Optional.empty());
        when(response.isCommitted()).thenReturn(true);
        doAnswer(invocation -> {
            assertThat(runtime.currentRequestId()).contains("request-42");
            assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("request-42");
            assertThat(runtime.currentCorrelationId()).isEmpty();
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(runtime.currentRequestId()).isEmpty();
        assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isNull();
    }

    @Test
    void writesTraceIdWhenPresentAfterChain() throws ServletException, java.io.IOException {
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-3");
        when(traceContextView.traceId()).thenReturn(Optional.of("trace-xyz"));
        when(response.isCommitted()).thenReturn(false);

        filter.doFilter(request, response, chain);

        verify(response).setHeader(PlatformHeaders.X_TRACE_ID, "trace-xyz");
    }

    @Test
    void writesTraceIdEvenWhenChainThrows() throws ServletException, java.io.IOException {
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-4");
        when(traceContextView.traceId()).thenReturn(Optional.of("trace-during-error"));
        when(response.isCommitted()).thenReturn(false);
        doThrow(new ServletException("boom")).when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class);

        verify(response).setHeader(PlatformHeaders.X_TRACE_ID, "trace-during-error");
    }

    @Test
    void exceptionBoundaryClearsRemoteServiceMirror() throws ServletException, java.io.IOException {
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-error");
        when(traceContextView.traceId()).thenReturn(Optional.of(TRACE_ID));
        when(response.isCommitted()).thenReturn(true);
        doThrow(new ServletException("boom")).when(chain).doFilter(request, response);
        RemoteServiceMdc.putIfPresent("billing", TRACE_ID);

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class);

        assertThat(new RemoteServiceNameResolver(List.of()).resolve(TRACE_ID)).isEmpty();
        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
    }

    @Test
    void skipsHeadersWhenResponseCommitted() throws ServletException, java.io.IOException {
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-5");
        when(traceContextView.traceId()).thenReturn(Optional.of("trace-broken-header"));
        when(response.isCommitted()).thenReturn(true);

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq(PlatformHeaders.X_TRACE_ID), anyString());
    }
}
