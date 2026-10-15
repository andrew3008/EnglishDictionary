package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
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
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

class TraceResponseHeaderWebFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);

    @BeforeAll
    static void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }

    @Test
    void skipsRequestIdHeaderWhenExposeDisabled_butStillWritesTraceId() {
        // given
        TracingProperties properties = new TracingProperties();
        properties.getRequestIdHeader().setEnabled(false);

        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithTraceId(), properties);
        MockServerWebExchange exchange = exchangeFor("/no-request-id", PlatformHeaders.X_REQUEST_ID, "req-hidden");
        WebFilterChain chain = ignored -> Mono.empty();

        // when
        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        // then
        assertThat(exchange.getResponse().getHeaders().getFirst(PlatformHeaders.X_REQUEST_ID)).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(PlatformHeaders.X_TRACE_ID)).isEqualTo(TRACE_ID);
    }

    @Test
    void bindsRequestIdForReactiveSubscription() {
        withReactiveIdentityPropagation(runtime -> {
            // given
            TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId());
            MockServerWebExchange exchange = exchangeFor("/identity", PlatformHeaders.X_REQUEST_ID, "request-42");
            WebFilterChain chain = ignored -> Mono.fromRunnable(() -> {
                assertThat(runtime.currentRequestId()).contains("request-42");
                assertThat(runtime.currentCorrelationId()).isEmpty();
            });

            // when
            filter.filter(exchange, chain).block();

            // then
            assertThat(runtime.currentRequestId()).isEmpty();
        });
    }

    @Test
    void ignoresValidInboundCorrelationIdWhenAcceptInboundDisabled() {
        withReactiveIdentityPropagation(runtime -> {
            // given
            TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId());
            MockServerWebExchange exchange =
                    exchangeFor("/correlation-disabled", PlatformHeaders.X_CORRELATION_ID, "trusted-correlation");
            WebFilterChain chain = ignored -> Mono.fromRunnable(() ->
                    assertThat(runtime.currentCorrelationId()).isEmpty()
            );

            // when/then
            filter.filter(exchange, chain).block();
        });
    }

    @Test
    void acceptsExactlyOneValidInboundCorrelationIdAsBaseScope() {
        withReactiveIdentityPropagation(runtime -> {
            // given
            TracingProperties properties = new TracingProperties();
            properties.getPropagation().getCorrelation().setAcceptInbound(true);

            TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId(), properties);
            MockServerWebExchange exchange =
                    exchangeFor("/correlation-accepted", PlatformHeaders.X_CORRELATION_ID, "trusted-correlation");
            WebFilterChain chain = ignored -> Mono.fromRunnable(() ->
                    assertThat(runtime.currentCorrelationId()).contains("trusted-correlation")
            );

            // when
            filter.filter(exchange, chain).block();

            // then
            assertThat(runtime.currentCorrelationId()).isEmpty();
        });
    }

    @Test
    void beforeCommitOverwritesDownstreamHeaderMutation() {
        // given
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithTraceId());
        MockServerWebExchange exchange = exchangeFor("/overwrite");
        WebFilterChain chain = current -> {
            current.getResponse().getHeaders().set(PlatformHeaders.X_TRACE_ID, "evil");
            current.getResponse().getHeaders().set("x-trace-id", "evil-lower");
            return Mono.empty();
        };

        // when
        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        // then
        assertThat(exchange.getResponse().getHeaders().get(PlatformHeaders.X_TRACE_ID)).containsExactly(TRACE_ID);
    }

    @Test
    void laterBeforeCommitCannotReplacePlatformOwnedTraceId() {
        // given
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithTraceId());
        MockServerWebExchange exchange = exchangeFor("/late-commit");
        WebFilterChain chain = current -> {
            current.getResponse().beforeCommit(() -> Mono.fromRunnable(() ->
                    current.getResponse().getHeaders().set(PlatformHeaders.X_TRACE_ID, "evil-late"))
            );
            return Mono.empty();
        };

        // when
        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        // then
        assertThat(exchange.getResponse().getHeaders().get(PlatformHeaders.X_TRACE_ID)).containsExactly(TRACE_ID);
    }

    @Test
    void writeAndFlushWithKeepsCanonicalTraceId() {
        // given
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithTraceId());
        MockServerWebExchange exchange = exchangeFor("/flush");
        WebFilterChain chain = current -> {
            current.getResponse().getHeaders().set(PlatformHeaders.X_TRACE_ID, "evil");
            return current.getResponse().writeAndFlushWith(Mono.empty());
        };

        // when
        filter.filter(exchange, chain).block();

        // then
        assertThat(exchange.getResponse().getHeaders().get(PlatformHeaders.X_TRACE_ID)).containsExactly(TRACE_ID);
    }

    @Test
    void writesSuccessResultOnCurrentSpanAfterSuccessfulChain() {
        // given/when
        SpanData data = runFilterWithCurrentSpan(ignored -> Mono.empty());

        // then
        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.SUCCESS.value());
    }

    @Test
    void writesFailureResultOnCurrentSpanWhenStatusIsClientError() {
        // given/when
        SpanData data = runFilterWithCurrentSpan(current -> {
            current.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return Mono.empty();
        });

        // then
        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
    }

    @Test
    void writesFailureResultOnCurrentSpanWhenChainErrors() {
        // given/when
        SpanData data = runFilterWithCurrentSpan(ignored -> Mono.error(new IllegalStateException("boom")));

        // then
        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
    }

    private static SpanData runFilterWithCurrentSpan(WebFilterChain chain) {
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId());
        MockServerWebExchange exchange = exchangeFor("/result");

        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Span span = provider.get("test").spanBuilder("http.server").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                filter.filter(exchange, chain)
                        .onErrorResume(error -> Mono.empty())
                        .block();
            }
            span.end();
            return exporter.getFinishedSpanItems().getFirst();
        }
    }

    private static void withReactiveIdentityPropagation(Consumer<TracingRuntime> scenario) {
        TracingRuntime runtime = NoOpTracingRuntime.noop();
        ReactiveIdentityContextPropagation propagation = new ReactiveIdentityContextPropagation(new RequestIdentityBoundarySupport(runtime));
        propagation.afterSingletonsInstantiated();
        try {
            scenario.accept(runtime);
        } finally {
            propagation.destroy();
        }
    }

    private static TraceOperations traceOperationsWithTraceId() {
        return traceOperationsReturning(TRACE_ID);
    }

    private static TraceOperations traceOperationsWithoutTraceId() {
        return traceOperationsReturning(null);
    }

    private static TraceOperations traceOperationsReturning(String traceId) {
        TraceOperations traceOperations = mock(TraceOperations.class);
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceOperations.traceContext()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(Optional.ofNullable(traceId));
        return traceOperations;
    }

    private static TraceResponseHeaderWebFilter filterWith(TraceOperations traceOperations) {
        return filterWith(traceOperations, new TracingProperties());
    }

    private static TraceResponseHeaderWebFilter filterWith(TraceOperations traceOperations,
                                                           TracingProperties properties) {
        return new TraceResponseHeaderWebFilter(
                traceOperations,
                properties,
                new DefaultInboundTraceControlExtractor()
        );
    }

    private static MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private static MockServerWebExchange exchangeFor(String path, String headerName, String headerValue) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(headerName, headerValue)
                .build());
    }
}
