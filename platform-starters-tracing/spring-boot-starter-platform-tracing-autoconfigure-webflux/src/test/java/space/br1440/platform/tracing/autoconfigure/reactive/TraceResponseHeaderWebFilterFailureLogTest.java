package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
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
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

class TraceResponseHeaderWebFilterFailureLogTest {

    private static final String SECRET = "response-filter-secret";
    private static final SpanContext VALID_SPAN_CONTEXT = SpanContext.create(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            TraceFlags.getSampled(),
            TraceState.getDefault()
    );
    private static final List<String> WARN_MESSAGES = List.of(
            "Failed to read the current trace id: java.lang.IllegalStateException",
            "Failed to set the request id span attribute: java.lang.IllegalStateException",
            "Failed to set the HTTP server result span attribute: java.lang.IllegalStateException"
    );

    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeAll
    static void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(TraceResponseHeaderWebFilter.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);

        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }

    @Test
    void independentlyBoundsAllFailureWarningsPerInstanceAndKeepsChain() {
        // given
        TraceOperations traceOperations = failingTraceOperations();
        Span span = failingAttributeSpan();
        TraceResponseHeaderWebFilter first = filterWith(traceOperations);
        AtomicInteger chainCalls = new AtomicInteger();
        WebFilterChain chain = ignored -> Mono.fromRunnable(chainCalls::incrementAndGet);

        // when
        execute(first, span, chain, "/first");
        execute(first, span, chain, "/second");
        TraceResponseHeaderWebFilter second = filterWith(traceOperations);
        execute(second, span, chain, "/third");

        // then
        assertThat(chainCalls).hasValue(3);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsOnly(Level.WARN)
                .hasSize(6);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactlyElementsOf(List.of(
                        WARN_MESSAGES.get(0), WARN_MESSAGES.get(1), WARN_MESSAGES.get(2),
                        WARN_MESSAGES.get(0), WARN_MESSAGES.get(1), WARN_MESSAGES.get(2)
                ))
                .allSatisfy(message -> assertThat(message).doesNotContain(SECRET));

        assertThat(appender.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    private static TraceOperations failingTraceOperations() {
        TraceOperations traceOperations = mock(TraceOperations.class);
        when(traceOperations.traceContext()).thenThrow(new IllegalStateException(SECRET));
        return traceOperations;
    }

    private static Span failingAttributeSpan() {
        Span span = mock(Span.class);
        when(span.storeInContext(any(Context.class))).thenCallRealMethod();
        when(span.getSpanContext()).thenReturn(VALID_SPAN_CONTEXT);
        when(span.isRecording()).thenReturn(true);
        when(span.setAttribute(anyString(), anyString())).thenAnswer(invocation -> {
            throw new IllegalStateException(SECRET + '-' + invocation.getArgument(0, String.class));
        });
        return span;
    }

    private static TraceResponseHeaderWebFilter filterWith(TraceOperations traceOperations) {
        return new TraceResponseHeaderWebFilter(
                traceOperations,
                new TracingProperties(),
                new DefaultInboundTraceControlExtractor()
        );
    }

    private static void execute(TraceResponseHeaderWebFilter filter,
                                Span span,
                                WebFilterChain chain,
                                String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        try (Scope ignored = Context.root().with(span).makeCurrent()) {
            filter.filter(exchange, chain).block();
        }
    }
}
