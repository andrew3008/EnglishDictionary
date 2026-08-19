package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

import reactor.core.publisher.Mono;

class TraceResponseHeaderWebFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @Test
    void skipsRequestIdHeaderWhenExposeDisabled_butStillWritesTraceId() {
        // given
        TraceOperations traceOperations = mock(TraceOperations.class);
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceOperations.traceContext()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(Optional.of(TRACE_ID));
        var properties = new TracingProperties();
        properties.getRequestIdHeader().setEnabled(false);
        var filter = new TraceResponseHeaderWebFilter(traceOperations, properties);
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
            var filter = new TraceResponseHeaderWebFilter(traceOperations, new TracingProperties());
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
}
