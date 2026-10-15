package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultTraceControlHeaderInjector;
import space.br1440.platform.tracing.runtime.propagation.control.OutboundApplicationIdentitySource;
import space.br1440.platform.tracing.webflux.ReactiveCorrelationOperations;

import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

class PlatformOutboundExchangeFilterFunctionCorrelationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ReactiveIdentityContextPropagation propagation;
    private ReactiveCorrelationOperations operations;
    private PlatformOutboundExchangeFilterFunction trustedFilter;
    private PlatformOutboundExchangeFilterFunction untrustedFilter;

    @BeforeAll
    static void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }

    @BeforeEach
    void setUp() {
        TracingRuntime runtime = NoOpTracingRuntime.disabledByConfiguration("reactive-outbound-correlation-test");
        RequestIdentityBoundarySupport boundary = new RequestIdentityBoundarySupport(runtime);
        propagation = new ReactiveIdentityContextPropagation(boundary);
        propagation.afterSingletonsInstantiated();
        operations = new DefaultReactiveCorrelationOperations(boundary);

        TraceOperations traceOperations = new DefaultTraceOperations(runtime);
        OutboundApplicationIdentitySource identitySource = OutboundApplicationIdentitySource.of(
                () -> traceOperations.traceContext().requestId().orElse(null),
                () -> traceOperations.traceContext().correlationId().orElse(null)
        );
        PlatformOutboundPropagation propagationPort = new DefaultTraceControlHeaderInjector(identitySource);

        OutboundPropagationPolicy allowAll = destination -> OutboundPropagationDecision.ALLOW_ALL;
        OutboundPropagationPolicy denyAll = destination -> OutboundPropagationDecision.DENY_ALL;
        trustedFilter = new PlatformOutboundExchangeFilterFunction(allowAll, propagationPort);
        untrustedFilter = new PlatformOutboundExchangeFilterFunction(denyAll, propagationPort);
    }

    @AfterEach
    void tearDown() {
        propagation.destroy();
    }

    @Test
    void nestedWithCorrelationIdOverridesOutboundHopThenRestoresOuterValue() {
        // given/when
        Mono<List<String>> chain = operations.withCorrelationId("outer", capture(trustedFilter)
                .flatMap(before -> operations.withCorrelationId("inner", capture(trustedFilter))
                        .flatMap(during -> capture(trustedFilter)
                                .map(after -> List.of(before, during, after)))));

        // then
        assertThat(chain.block(TIMEOUT)).containsExactly("outer", "inner", "outer");
    }

    @Test
    void untrustedDestinationNeverEmitsCorrelationHeaderEvenInsideScope() {
        // given/when
        String observed = operations.withCorrelationId("secret-correlation", capture(untrustedFilter)).block(TIMEOUT);

        // then
        assertThat(observed).isNull();
    }

    @Test
    void removeBeforeApplyStripsCallerSuppliedHeaderOnUntrustedDestination() {
        // given
        AtomicReference<String> observed = new AtomicReference<>();
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://untrusted.example/hop"))
                .header(PlatformHeaders.X_CORRELATION_ID, "caller-supplied-secret")
                .build();
        ExchangeFunction next = capturingExchangeFunction(observed);

        // when
        untrustedFilter.filter(request, next).block(TIMEOUT);

        // then
        assertThat(observed.get()).isNull();
    }

    private static Mono<String> capture(PlatformOutboundExchangeFilterFunction filter) {
        return Mono.defer(() -> {
            AtomicReference<String> observed = new AtomicReference<>();
            ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://trusted.internal/hop")).build();
            return filter.filter(request, capturingExchangeFunction(observed))
                    .flatMap(ignored -> Mono.fromSupplier(observed::get));
        });
    }

    private static ExchangeFunction capturingExchangeFunction(AtomicReference<String> observed) {
        return request -> {
            observed.set(request.headers().getFirst(PlatformHeaders.X_CORRELATION_ID));
            return Mono.just(mock(ClientResponse.class));
        };
    }
}
