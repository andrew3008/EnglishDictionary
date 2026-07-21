package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.api.span.builder.ActiveTraceContextView;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.core.mdc.remote.RemoteServiceMdc;
import space.br1440.platform.tracing.core.mdc.remote.RemoteServiceNameResolver;

class TraceResponseHeaderWebFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

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
}
