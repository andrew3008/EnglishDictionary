package space.br1440.platform.tracing.autoconfigure.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Optional;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultInboundTraceControlExtractor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

class TraceResponseHeaderServletFilterFailureLogTest {

    private static final String REQUEST_ID = "request-id";
    private static final String TRACE_CONTEXT_FAILURE_MESSAGE = "trace-context-secret-message";
    private static final String REQUEST_ATTRIBUTE_FAILURE_MESSAGE = "request-attribute-secret-message";
    private static final String RESULT_ATTRIBUTE_FAILURE_MESSAGE = "result-attribute-secret-message";
    private static final String TRACE_CONTEXT_WARN = "Failed to read the current trace id: java.lang.IllegalStateException";
    private static final String REQUEST_ATTRIBUTE_WARN = "Failed to set the request id span attribute: java.lang.IllegalStateException";
    private static final String RESULT_ATTRIBUTE_WARN = "Failed to set the HTTP server result span attribute: java.lang.IllegalStateException";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger(TraceResponseHeaderServletFilter.class);
        logger.addAppender(appender);

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        when(request.getHeader(PlatformHeaders.X_REQUEST_ID)).thenReturn(REQUEST_ID);
        when(response.isCommitted()).thenReturn(true);
        when(response.getStatus()).thenReturn(200);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void traceContextFailureWarnIsBoundedPerInstanceAndChainContinues() throws Exception {
        // given
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceContext.traceId()).thenThrow(new IllegalStateException(TRACE_CONTEXT_FAILURE_MESSAGE));
        TraceResponseHeaderServletFilter first = filter(traceContext);

        // when
        first.doFilter(request, response, chain);
        first.doFilter(request, response, chain);

        // then
        assertThat(appender.list).hasSize(1);

        // when
        filter(traceContext).doFilter(request, response, chain);

        // then
        verify(chain, times(3)).doFilter(eq(request), any(HttpServletResponse.class));
        assertBoundedWarnings(TRACE_CONTEXT_WARN, TRACE_CONTEXT_FAILURE_MESSAGE);
    }

    @Test
    void requestAttributeFailureWarnIsBoundedPerInstanceAndChainContinues() throws Exception {
        // given
        ActiveTraceContextView traceContext = emptyTraceContext();
        TraceResponseHeaderServletFilter first = filter(traceContext);
        Span span = currentSpan();
        doThrow(new IllegalStateException(REQUEST_ATTRIBUTE_FAILURE_MESSAGE))
                .when(span)
                .setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, REQUEST_ID);

        // when
        try (Scope ignored = Context.current().with(span).makeCurrent()) {
            first.doFilter(request, response, chain);
            first.doFilter(request, response, chain);

            // then
            assertThat(appender.list).hasSize(1);

            // when
            filter(traceContext).doFilter(request, response, chain);
        }

        // then
        verify(chain, times(3)).doFilter(eq(request), any(HttpServletResponse.class));
        assertBoundedWarnings(REQUEST_ATTRIBUTE_WARN, REQUEST_ATTRIBUTE_FAILURE_MESSAGE);
    }

    @Test
    void resultAttributeFailureWarnIsBoundedPerInstanceAndChainContinues() throws Exception {
        // given
        ActiveTraceContextView traceContext = emptyTraceContext();
        TraceResponseHeaderServletFilter first = filter(traceContext);
        Span span = currentSpan();
        doThrow(new IllegalStateException(RESULT_ATTRIBUTE_FAILURE_MESSAGE))
                .when(span)
                .setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());

        // when
        try (Scope ignored = Context.current().with(span).makeCurrent()) {
            first.doFilter(request, response, chain);
            first.doFilter(request, response, chain);

            // then
            assertThat(appender.list).hasSize(1);

            // when
            filter(traceContext).doFilter(request, response, chain);
        }

        // then
        verify(chain, times(3)).doFilter(eq(request), any(HttpServletResponse.class));
        assertBoundedWarnings(RESULT_ATTRIBUTE_WARN, RESULT_ATTRIBUTE_FAILURE_MESSAGE);
    }

    private TraceResponseHeaderServletFilter filter(ActiveTraceContextView traceContext) {
        TraceOperations traceOperations = mock(TraceOperations.class);
        when(traceOperations.traceContext()).thenReturn(traceContext);
        return new TraceResponseHeaderServletFilter(
                traceOperations,
                new TracingProperties(),
                new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop()),
                new DefaultInboundTraceControlExtractor()
        );
    }

    private static ActiveTraceContextView emptyTraceContext() {
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceContext.traceId()).thenReturn(Optional.empty());
        return traceContext;
    }

    private static Span currentSpan() {
        Span span = mock(Span.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(span.getSpanContext()).thenReturn(SpanContext.create(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                TraceFlags.getSampled(),
                TraceState.getDefault()
        ));
        return span;
    }

    private void assertBoundedWarnings(String warning, String failureMessage) {
        assertThat(appender.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(warning);
            assertThat(event.getFormattedMessage()).doesNotContain(failureMessage);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
