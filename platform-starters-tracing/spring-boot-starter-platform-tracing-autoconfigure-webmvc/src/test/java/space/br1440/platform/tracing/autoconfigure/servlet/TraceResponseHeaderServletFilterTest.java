package space.br1440.platform.tracing.autoconfigure.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultInboundTraceControlExtractor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class TraceResponseHeaderServletFilterTest {

    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);

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

        TraceOperations traceOperations = mock(TraceOperations.class);
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
                new RequestIdentityBoundarySupport(runtime),
                new DefaultInboundTraceControlExtractor()
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
        }).when(chain).doFilter(eq(request), any(HttpServletResponse.class));

        filter.doFilter(request, response, chain);

        verify(response).setHeader(eq(PlatformHeaders.X_REQUEST_ID), eq("req-incoming-1"));
        verify(response).setHeader(eq(PlatformHeaders.X_TRACE_ID), eq("trace-abc-123"));
        verify(chain).doFilter(eq(request), any(HttpServletResponse.class));
        assertThat(seenInsideChain.get()).isEmpty();
    }

    @Test
    void correlationIdWrittenButNoTraceIdWhenTraceAbsent() throws ServletException, java.io.IOException {
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-2");
        when(traceContextView.traceId()).thenReturn(Optional.empty());
        when(response.isCommitted()).thenReturn(false);

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq(PlatformHeaders.X_TRACE_ID), anyString());
        verify(response).setHeader(eq(PlatformHeaders.X_REQUEST_ID), eq("req-2"));
    }

    @Test
    void skipsRequestIdHeaderWhenExposeDisabled_butStillWritesTraceId() throws ServletException, java.io.IOException {
        // given
        properties.getRequestIdHeader().setEnabled(false);
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-hidden");
        when(traceContextView.traceId()).thenReturn(Optional.of("trace-keep"));
        when(response.isCommitted()).thenReturn(false);

        // when
        filter.doFilter(request, response, chain);

        // then
        verify(response, never()).setHeader(eq(PlatformHeaders.X_REQUEST_ID), anyString());
        verify(response).setHeader(PlatformHeaders.X_TRACE_ID, "trace-keep");
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
        }).when(chain).doFilter(eq(request), any(HttpServletResponse.class));

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
        doThrow(new ServletException("boom")).when(chain).doFilter(eq(request), any(HttpServletResponse.class));

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class);

        verify(response).setHeader(PlatformHeaders.X_TRACE_ID, "trace-during-error");
    }

    @Test
    void skipsHeadersWhenResponseCommitted() throws ServletException, java.io.IOException {
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-5");
        when(traceContextView.traceId()).thenReturn(Optional.of("trace-broken-header"));
        when(response.isCommitted()).thenReturn(true);

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq(PlatformHeaders.X_TRACE_ID), anyString());
    }

    @Test
    void ignoresValidInboundCorrelationIdWhenAcceptInboundDisabled() throws ServletException, java.io.IOException {
        // given
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-6");
        when(request.getHeaders(eq(PlatformHeaders.X_CORRELATION_ID)))
                .thenReturn(Collections.enumeration(List.of("trusted-correlation")));
        when(traceContextView.traceId()).thenReturn(Optional.empty());
        when(response.isCommitted()).thenReturn(false);
        AtomicReference<Optional<String>> seenInsideChain = new AtomicReference<>();
        doAnswer(invocation -> {
            seenInsideChain.set(runtime.currentCorrelationId());
            return null;
        }).when(chain).doFilter(eq(request), any(HttpServletResponse.class));

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(seenInsideChain.get()).isEmpty();
    }

    @Test
    void acceptsExactlyOneValidInboundCorrelationIdAsBaseScope() throws ServletException, java.io.IOException {
        // given
        properties.getPropagation().getCorrelation().setAcceptInbound(true);
        when(request.getHeader(eq(PlatformHeaders.X_REQUEST_ID))).thenReturn("req-7");
        when(request.getHeaders(eq(PlatformHeaders.X_CORRELATION_ID)))
                .thenReturn(Collections.enumeration(List.of("trusted-correlation")));
        when(traceContextView.traceId()).thenReturn(Optional.empty());
        when(response.isCommitted()).thenReturn(false);
        AtomicReference<Optional<String>> seenInsideChain = new AtomicReference<>();
        doAnswer(invocation -> {
            seenInsideChain.set(runtime.currentCorrelationId());
            return null;
        }).when(chain).doFilter(eq(request), any(HttpServletResponse.class));

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(seenInsideChain.get()).contains("trusted-correlation");
        assertThat(runtime.currentCorrelationId()).isEmpty();
    }

    @Test
    void writesSuccessResultOnCurrentSpanAfterSuccessfulChain() throws Exception {
        when(traceContextView.traceId()).thenReturn(Optional.empty());
        when(response.isCommitted()).thenReturn(false);
        when(response.getStatus()).thenReturn(200);

        SpanData data = runFilterWithCurrentSpan();

        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.SUCCESS.value());
    }

    @Test
    void writesFailureResultOnCurrentSpanWhenStatusIsClientError() throws Exception {
        when(traceContextView.traceId()).thenReturn(Optional.empty());
        when(response.isCommitted()).thenReturn(false);
        when(response.getStatus()).thenReturn(400);

        SpanData data = runFilterWithCurrentSpan();

        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
    }

    @Test
    void writesFailureResultOnCurrentSpanWhenChainThrows() throws Exception {
        when(traceContextView.traceId()).thenReturn(Optional.empty());
        when(response.isCommitted()).thenReturn(false);
        when(response.getStatus()).thenReturn(200);
        doThrow(new ServletException("boom")).when(chain).doFilter(eq(request), any(HttpServletResponse.class));

        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Span span = provider.get("test").spanBuilder("http.server").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                        .isInstanceOf(ServletException.class);
            }
            span.end();

            assertThat(exporter.getFinishedSpanItems().getFirst().getAttributes().get(RESULT_KEY))
                    .isEqualTo(SpanResult.FAILURE.value());
        }
    }

    private SpanData runFilterWithCurrentSpan() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Span span = provider.get("test").spanBuilder("http.server").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                filter.doFilter(request, response, chain);
            }
            span.end();
            return exporter.getFinishedSpanItems().getFirst();
        }
    }
}
