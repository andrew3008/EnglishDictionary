package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.Disposable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceMdc;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceNameResolver;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

class TraceResponseHeaderWebFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @BeforeAll
    static void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        RemoteServiceMdc.clearForTrace(TRACE_ID);
    }

    @Test
    void cancellationBoundaryClearsRemoteServiceMirror() {
        TraceOperations traceOperations = mock(TraceOperations.class);
        ActiveTraceContextView traceContext = mock(ActiveTraceContextView.class);
        when(traceOperations.traceContext()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(Optional.of(TRACE_ID));

        var properties = new TracingProperties();
        var filter = new TraceResponseHeaderWebFilter(traceOperations, properties);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/cancelled").build());
        WebFilterChain chain = ignored -> Mono.never();
        RemoteServiceMdc.putIfPresent("billing", TRACE_ID);

        Disposable subscription = filter.filter(exchange, chain).subscribe();
        subscription.dispose();

        assertThat(new RemoteServiceNameResolver(List.of()).resolve(TRACE_ID)).isEmpty();
        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
    }

    @Test
    void bindsRequestIdForReactiveSubscriptionAndCleansBridge() throws Exception {
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
