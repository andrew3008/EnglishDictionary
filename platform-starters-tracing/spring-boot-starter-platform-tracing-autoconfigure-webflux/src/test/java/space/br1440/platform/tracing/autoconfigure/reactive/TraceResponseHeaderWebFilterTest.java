package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
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
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

class TraceResponseHeaderWebFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final AttributeKey<String> RESULT_KEY =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);

    @BeforeAll
    static void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }

    @Test
    void skipsRequestIdHeaderWhenExposeDisabled_butStillWritesTraceId() {
        // given
        TraceOperations traceOperations = mock(TraceOperations.class);
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceOperations.traceContext()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(Optional.of(TRACE_ID));
        var properties = new TracingProperties();
        properties.getRequestIdHeader().setEnabled(false);
        var filter = new TraceResponseHeaderWebFilter(traceOperations, properties, new DefaultInboundTraceControlExtractor());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/no-request-id")
                .header("X-Request-Id", "req-hidden")
                .build());
        WebFilterChain chain = ignored -> Mono.empty();

        // when
        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        // then
        assertThat(exchange.getResponse().getHeaders().getFirst(properties.getRequestIdHeader().getName())).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id")).isEqualTo(TRACE_ID);
    }

    @Test
    void bindsRequestIdForReactiveSubscription() {
        TracingRuntime runtime = NoOpTracingRuntime.noop();
        RequestIdentityBoundarySupport boundary = new RequestIdentityBoundarySupport(runtime);
        ReactiveIdentityContextPropagation propagation = new ReactiveIdentityContextPropagation(boundary);
        propagation.afterSingletonsInstantiated();
        try {
            TraceOperations traceOperations = mock(TraceOperations.class);
            ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
            when(traceOperations.traceContext()).thenReturn(traceContext);
            when(traceContext.traceId()).thenReturn(Optional.empty());
            var filter = new TraceResponseHeaderWebFilter(traceOperations, new TracingProperties(), new DefaultInboundTraceControlExtractor());
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/identity")
                    .header("X-Request-Id", "request-42")
                    .build());
            WebFilterChain chain = ignored -> Mono.fromRunnable(() -> {
                assertThat(runtime.currentRequestId()).contains("request-42");
                assertThat(runtime.currentCorrelationId()).isEmpty();
            });

            filter.filter(exchange, chain).block();

            assertThat(runtime.currentRequestId()).isEmpty();
        } finally {
            propagation.destroy();
        }
    }

    @Test
    void ignoresValidInboundCorrelationIdWhenAcceptInboundDisabled() {
        TracingRuntime runtime = NoOpTracingRuntime.noop();
        RequestIdentityBoundarySupport boundary = new RequestIdentityBoundarySupport(runtime);
        ReactiveIdentityContextPropagation propagation = new ReactiveIdentityContextPropagation(boundary);
        propagation.afterSingletonsInstantiated();
        try {
            // given
            TraceOperations traceOperations = mock(TraceOperations.class);
            ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
            when(traceOperations.traceContext()).thenReturn(traceContext);
            when(traceContext.traceId()).thenReturn(Optional.empty());
            var filter = new TraceResponseHeaderWebFilter(traceOperations, new TracingProperties(), new DefaultInboundTraceControlExtractor());
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/correlation-disabled")
                    .header("X-Correlation-Id", "trusted-correlation")
                    .build());
            WebFilterChain chain = ignored -> Mono.fromRunnable(() ->
                    assertThat(runtime.currentCorrelationId()).isEmpty());

            // when/then
            filter.filter(exchange, chain).block();
        } finally {
            propagation.destroy();
        }
    }

    @Test
    void acceptsExactlyOneValidInboundCorrelationIdAsBaseScope() {
        TracingRuntime runtime = NoOpTracingRuntime.noop();
        RequestIdentityBoundarySupport boundary = new RequestIdentityBoundarySupport(runtime);
        ReactiveIdentityContextPropagation propagation = new ReactiveIdentityContextPropagation(boundary);
        propagation.afterSingletonsInstantiated();
        try {
            // given
            TraceOperations traceOperations = mock(TraceOperations.class);
            ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
            when(traceOperations.traceContext()).thenReturn(traceContext);
            when(traceContext.traceId()).thenReturn(Optional.empty());
            var properties = new TracingProperties();
            properties.getPropagation().getCorrelation().setAcceptInbound(true);
            var filter = new TraceResponseHeaderWebFilter(traceOperations, properties, new DefaultInboundTraceControlExtractor());
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/correlation-accepted")
                    .header("X-Correlation-Id", "trusted-correlation")
                    .build());
            WebFilterChain chain = ignored -> Mono.fromRunnable(() ->
                    assertThat(runtime.currentCorrelationId()).contains("trusted-correlation"));

            // when
            filter.filter(exchange, chain).block();

            // then
            assertThat(runtime.currentCorrelationId()).isEmpty();
        } finally {
            propagation.destroy();
        }
    }

    @Test
    void beforeCommitOverwritesDownstreamHeaderMutation() {
        // given
        TraceOperations traceOperations = mock(TraceOperations.class);
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceOperations.traceContext()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(Optional.of(TRACE_ID));
        var filter = new TraceResponseHeaderWebFilter(traceOperations, new TracingProperties(), new DefaultInboundTraceControlExtractor());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/overwrite").build());
        WebFilterChain chain = current -> {
            current.getResponse().getHeaders().set("X-Trace-Id", "evil");
            current.getResponse().getHeaders().set("x-trace-id", "evil-lower");
            return Mono.empty();
        };

        // when
        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        // then
        assertThat(exchange.getResponse().getHeaders().get("X-Trace-Id")).containsExactly(TRACE_ID);
    }

    @Test
    void laterBeforeCommitCannotReplacePlatformOwnedTraceId() {
        // given
        TraceOperations traceOperations = mock(TraceOperations.class);
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceOperations.traceContext()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(Optional.of(TRACE_ID));
        var filter = new TraceResponseHeaderWebFilter(traceOperations, new TracingProperties(), new DefaultInboundTraceControlExtractor());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/late-commit").build());
        WebFilterChain chain = current -> {
            current.getResponse().beforeCommit(() -> Mono.fromRunnable(() ->
                    current.getResponse().getHeaders().set("X-Trace-Id", "evil-late")));
            return Mono.empty();
        };

        // when
        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        // then
        assertThat(exchange.getResponse().getHeaders().get("X-Trace-Id")).containsExactly(TRACE_ID);
    }

    @Test
    void writeAndFlushWithKeepsCanonicalTraceId() {
        // given
        TraceOperations traceOperations = mock(TraceOperations.class);
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceOperations.traceContext()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(Optional.of(TRACE_ID));
        var filter = new TraceResponseHeaderWebFilter(traceOperations, new TracingProperties(), new DefaultInboundTraceControlExtractor());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/flush").build());
        WebFilterChain chain = current -> {
            current.getResponse().getHeaders().set("X-Trace-Id", "evil");
            return current.getResponse().writeAndFlushWith(Mono.empty());
        };

        // when
        filter.filter(exchange, chain).block();

        // then
        assertThat(exchange.getResponse().getHeaders().get("X-Trace-Id")).containsExactly(TRACE_ID);
    }

    @Test
    void writesSuccessResultOnCurrentSpanAfterSuccessfulChain() {
        SpanData data = runFilterWithCurrentSpan(ignored -> Mono.empty());

        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.SUCCESS.value());
    }

    @Test
    void writesFailureResultOnCurrentSpanWhenStatusIsClientError() {
        SpanData data = runFilterWithCurrentSpan(current -> {
            current.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return Mono.empty();
        });

        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
    }

    @Test
    void writesFailureResultOnCurrentSpanWhenChainErrors() {
        SpanData data = runFilterWithCurrentSpan(ignored -> Mono.error(new IllegalStateException("boom")));

        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
    }

    private static SpanData runFilterWithCurrentSpan(WebFilterChain chain) {
        TraceOperations traceOperations = mock(TraceOperations.class);
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceOperations.traceContext()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(Optional.empty());
        var filter = new TraceResponseHeaderWebFilter(
                traceOperations, new TracingProperties(), new DefaultInboundTraceControlExtractor());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/result").build());

        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try {
            Span span = provider.get("test").spanBuilder("http.server").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                filter.filter(exchange, chain)
                        .onErrorResume(error -> Mono.empty())
                        .block();
            }
            span.end();
            return exporter.getFinishedSpanItems().getFirst();
        } finally {
            provider.close();
        }
    }
}
