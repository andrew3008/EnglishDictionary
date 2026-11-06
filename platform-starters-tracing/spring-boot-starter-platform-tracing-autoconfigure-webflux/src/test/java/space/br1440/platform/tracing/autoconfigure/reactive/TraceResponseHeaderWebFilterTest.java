package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultInboundTraceControlExtractor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class TraceResponseHeaderWebFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String OUTCOME_REQUEST_ID = "req-outcome";
    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);
    private static final AttributeKey<String> REQUEST_ID_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REQUEST_ID);
    private static final AttributeKey<String> CORRELATION_ID_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID);
    private static final AttributeKey<String> ERROR_TYPE_KEY = AttributeKey.stringKey("error.type");

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
    void edgeResolvedRequestIdReplacesPreSeededReactorRequestId() {
        withReactiveIdentityPropagation(runtime -> {
            TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId());
            MockServerWebExchange exchange = exchangeFor("/preseeded", PlatformHeaders.X_REQUEST_ID, "edge-request");
            WebFilterChain chain = ignored -> Mono.fromRunnable(() ->
                    assertThat(runtime.currentRequestId()).contains("edge-request")
            );

            filter.filter(exchange, chain)
                    .contextWrite(context -> context.put(
                            ReactiveIdentityContextPropagation.KEY,
                            new ReactiveIdentityContextPropagation.IdentityState(
                                    "preseeded-request", null, false, false)))
                    .block();
        });
    }

    @Test
    void absentHeaderReplacesPreSeededReactorRequestId() {
        withReactiveIdentityPropagation(runtime -> {
            TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId());
            MockServerWebExchange exchange = exchangeFor("/preseeded-absent");
            WebFilterChain chain = ignored -> Mono.fromRunnable(() -> {
                assertThat(runtime.currentRequestId()).isPresent();
                assertThat(runtime.currentRequestId().orElseThrow()).isNotEqualTo("preseeded-request");
            });

            filter.filter(exchange, chain)
                    .contextWrite(context -> context.put(
                            ReactiveIdentityContextPropagation.KEY,
                            new ReactiveIdentityContextPropagation.IdentityState(
                                    "preseeded-request", null, false, false)))
                    .block();
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
    void writesAcceptedCorrelationIdOnTheCurrentSpan() {
        // given
        TracingProperties properties = new TracingProperties();
        properties.getPropagation().getCorrelation().setAcceptInbound(true);
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId(), properties);
        MockServerWebExchange exchange = exchangeFor(
                "/correlation-span", PlatformHeaders.X_CORRELATION_ID, "trusted-correlation");

        // when
        SpanData data = runWithCurrentSpan(() -> filter.filter(exchange, chain()).block());

        // then
        assertThat(data.getAttributes().get(CORRELATION_ID_KEY)).isEqualTo("trusted-correlation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "a,b"})
    void doesNotWriteCorrelationAttributeForInvalidHeader(String correlationId) {
        // given
        TracingProperties properties = new TracingProperties();
        properties.getPropagation().getCorrelation().setAcceptInbound(true);
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId(), properties);
        MockServerWebExchange exchange = exchangeFor("/correlation-invalid", PlatformHeaders.X_CORRELATION_ID, correlationId);

        // when
        SpanData data = runWithCurrentSpan(() -> filter.filter(exchange, chain()).block());

        // then
        assertThat(data.getAttributes().get(CORRELATION_ID_KEY)).isNull();
    }

    @Test
    void doesNotWriteCorrelationAttributeForDuplicateMissingOrDisabledHeader() {
        // given
        TracingProperties accepting = new TracingProperties();
        accepting.getPropagation().getCorrelation().setAcceptInbound(true);
        TraceResponseHeaderWebFilter acceptingFilter = filterWith(traceOperationsWithoutTraceId(), accepting);
        MockServerWebExchange duplicated = MockServerWebExchange.from(MockServerHttpRequest.get("/correlation-duplicated")
                .header(PlatformHeaders.X_CORRELATION_ID, "first-value", "second-value")
                .header("baggage", "platform.correlation.id=spoofed")
                .build());
        MockServerWebExchange missing = exchangeFor("/correlation-missing");
        TraceResponseHeaderWebFilter disabledFilter = filterWith(traceOperationsWithoutTraceId());
        MockServerWebExchange disabled = exchangeFor(
                "/correlation-disabled-span", PlatformHeaders.X_CORRELATION_ID, "trusted-correlation");

        // when
        SpanData duplicatedData = runWithCurrentSpan(() -> acceptingFilter.filter(duplicated, chain()).block());
        SpanData missingData = runWithCurrentSpan(() -> acceptingFilter.filter(missing, chain()).block());
        SpanData disabledData = runWithCurrentSpan(() -> disabledFilter.filter(disabled, chain()).block());

        // then
        assertThat(duplicatedData.getAttributes().get(CORRELATION_ID_KEY)).isNull();
        assertThat(missingData.getAttributes().get(CORRELATION_ID_KEY)).isNull();
        assertThat(disabledData.getAttributes().get(CORRELATION_ID_KEY)).isNull();
    }

    private static WebFilterChain chain() {
        return ignored -> Mono.empty();
    }

    @Test
    void beforeCommitOverwritesDownstreamHeaderMutation() {
        // given
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithTraceId());
        MockServerWebExchange exchange = exchangeFor("/overwrite");
        WebFilterChain chain = current -> {
            current.getResponse().getHeaders().set(PlatformHeaders.X_TRACE_ID, "evil");
            current.getResponse().getHeaders().set(PlatformHeaders.X_TRACE_ID.toLowerCase(Locale.ROOT), "evil-lower");
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

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"OK", "BAD_REQUEST", "INTERNAL_SERVER_ERROR"})
    void doesNotWriteHttpOutcomeOnCurrentSpan(HttpStatus status) {
        // given
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId());
        MockServerWebExchange exchange = exchangeFor("/outcome", PlatformHeaders.X_REQUEST_ID, OUTCOME_REQUEST_ID);
        WebFilterChain chain = current -> {
            current.getResponse().setStatusCode(status);
            return Mono.empty();
        };

        // when
        SpanData data = runWithCurrentSpan(() -> filter.filter(exchange, chain).block());

        // then
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
        assertNoHttpOutcome(data);
    }

    @Test
    void doesNotWriteHttpOutcomeWhenChainErrors() {
        // given
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId());
        MockServerWebExchange exchange = exchangeFor("/outcome", PlatformHeaders.X_REQUEST_ID, OUTCOME_REQUEST_ID);
        WebFilterChain chain = ignored -> Mono.error(new IllegalStateException("boom"));

        // when
        SpanData data = runWithCurrentSpan(() -> assertThatThrownBy(() -> filter.filter(exchange, chain).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom"));

        // then
        assertNoHttpOutcome(data);
    }

    @Test
    void doesNotWriteHttpOutcomeWhenSubscriptionIsCancelled() {
        // given
        TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithoutTraceId());
        MockServerWebExchange exchange = exchangeFor("/outcome", PlatformHeaders.X_REQUEST_ID, OUTCOME_REQUEST_ID);
        AtomicBoolean cancelled = new AtomicBoolean();
        WebFilterChain chain = ignored -> Mono.<Void>never().doOnCancel(() -> cancelled.set(true));

        // when
        SpanData data = runWithCurrentSpan(() -> filter.filter(exchange, chain).subscribe().dispose());

        // then
        assertThat(cancelled).isTrue();
        assertNoHttpOutcome(data);
    }

    @Test
    void keepsIdentityContextAndHeadersAcrossPublishOn() {
        withReactiveIdentityPropagation(runtime -> {
            // given
            TraceResponseHeaderWebFilter filter = filterWith(traceOperationsWithTraceId());
            MockServerWebExchange exchange = exchangeFor("/publish-on", PlatformHeaders.X_REQUEST_ID, "request-async");
            AtomicReference<String> contextRequestId = new AtomicReference<>();
            AtomicReference<Optional<String>> boundRequestId = new AtomicReference<>();
            WebFilterChain chain = ignored -> Mono.just("payload")
                    .publishOn(Schedulers.parallel())
                    .doOnNext(value -> boundRequestId.set(runtime.currentRequestId()))
                    .flatMap(value -> Mono.deferContextual(context -> {
                        ReactiveIdentityContextPropagation.IdentityState state =
                                context.get(ReactiveIdentityContextPropagation.KEY);
                        contextRequestId.set(state.requestId());
                        return Mono.empty();
                    }))
                    .then();

            // when
            filter.filter(exchange, chain).block(Duration.ofSeconds(5));
            exchange.getResponse().setComplete().block(Duration.ofSeconds(5));

            // then
            assertThat(contextRequestId).hasValue("request-async");
            assertThat(boundRequestId.get()).contains("request-async");
            assertThat(exchange.getResponse().getHeaders().getFirst(PlatformHeaders.X_REQUEST_ID))
                    .isEqualTo("request-async");
            assertThat(exchange.getResponse().getHeaders().get(PlatformHeaders.X_TRACE_ID)).containsExactly(TRACE_ID);
            assertThat(runtime.currentRequestId()).isEmpty();
        });
    }

    private static SpanData runWithCurrentSpan(Runnable execution) {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Span span = provider.get("test").spanBuilder("http.server").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                execution.run();
            }
            span.end();
            return exporter.getFinishedSpanItems().getFirst();
        }
    }

    private static void assertNoHttpOutcome(SpanData data) {
        assertThat(data.getAttributes().get(REQUEST_ID_KEY)).isEqualTo(OUTCOME_REQUEST_ID);
        assertThat(data.getAttributes().get(RESULT_KEY)).isNull();
        assertThat(data.getAttributes().get(ERROR_TYPE_KEY)).isNull();
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(data.getEvents()).isEmpty();
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
