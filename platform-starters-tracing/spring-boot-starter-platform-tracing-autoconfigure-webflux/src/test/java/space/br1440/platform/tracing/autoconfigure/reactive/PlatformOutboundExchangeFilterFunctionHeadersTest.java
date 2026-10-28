package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

import reactor.core.publisher.Mono;

class PlatformOutboundExchangeFilterFunctionHeadersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final OutboundPropagationHeaders ALL_HEADERS = new OutboundPropagationHeaders(
            Optional.of("on"), Optional.of("1"), Optional.of("request-from-port"), Optional.of("correlation-from-port"));
    private static final OutboundPropagationHeaders PARTIAL_HEADERS = new OutboundPropagationHeaders(
            Optional.empty(), Optional.of("1"), Optional.of("request-from-port"), Optional.of("correlation-from-port"));

    @ParameterizedTest
    @MethodSource("headerCases")
    void reservedHeadersMatchOnlyPortResult(Scenario scenario, boolean preset) {
        // given
        ClientRequest request = request(preset);
        AtomicInteger policyCalls = new AtomicInteger();
        AtomicInteger resolveCalls = new AtomicInteger();
        PlatformOutboundExchangeFilterFunction filter = filter(scenario, policyCalls, resolveCalls);
        List<ClientRequest> exchanged = new ArrayList<>();
        ClientResponse response = mock(ClientResponse.class);

        // when
        ClientResponse actual = exchange(filter, request, exchanged, response);

        // then
        assertThat(actual).isSameAs(response);
        assertThat(policyCalls).hasValue(1);
        assertThat(resolveCalls).hasValue(scenario == Scenario.POLICY_FAILURE ? 0 : 1);
        assertThat(exchanged).singleElement().satisfies(current -> assertExchanged(current, request, scenario.headers));
        assertOriginalRequest(request, preset);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void downstreamFailureIsPropagatedWithoutSecondExchange(boolean synchronous) {
        // given
        IllegalStateException failure = new IllegalStateException("Downstream exchange failed");
        AtomicInteger exchanges = new AtomicInteger();
        PlatformOutboundExchangeFilterFunction filter = new PlatformOutboundExchangeFilterFunction(
                destination -> OutboundPropagationDecision.ALLOW_ALL, (decision, control) -> ALL_HEADERS);
        ExchangeFunction next = current -> {
            exchanges.incrementAndGet();
            assertHeaders(current.headers(), ALL_HEADERS);
            if (synchronous) {
                throw failure;
            }
            return Mono.error(failure);
        };

        // when
        Throwable actual = catchThrowable(
                () -> filter.filter(request(true), next).block(TIMEOUT));

        // then
        assertThat(actual).isSameAs(failure);
        assertThat(exchanges).hasValue(1);
    }

    @Test
    void resolvesControlFromContextForEverySubscription() {
        // given
        List<InboundTraceControl> controls = new ArrayList<>();
        List<ClientRequest> exchanged = new ArrayList<>();
        Mono<ClientResponse> filtered = filter(controls).filter(request(true), current -> {
            exchanged.add(current);
            return Mono.just(mock(ClientResponse.class));
        });
        assertThat(controls).isEmpty();
        assertThat(exchanged).isEmpty();

        // when
        subscribe(filtered, true, false);
        subscribe(filtered, false, true);

        // then
        assertThat(controls).extracting(InboundTraceControl::forceTrace).containsExactly(true, false);
        assertThat(controls).extracting(InboundTraceControl::qaTrace).containsExactly(false, true);
        assertThat(exchanged).hasSize(2);
        assertHeaders(exchanged.get(0).headers(), controlHeaders(true, false));
        assertHeaders(exchanged.get(1).headers(), controlHeaders(false, true));
    }

    private static PlatformOutboundExchangeFilterFunction filter(Scenario scenario,
                                                                 AtomicInteger policyCalls,
                                                                 AtomicInteger resolveCalls) {
        return new PlatformOutboundExchangeFilterFunction(
                policy(scenario, policyCalls), propagation(scenario, resolveCalls));
    }

    private static OutboundPropagationPolicy policy(Scenario scenario, AtomicInteger policyCalls) {
        return destination -> {
            policyCalls.incrementAndGet();
            assertThat(destination).isEqualTo("orders.internal");
            if (scenario == Scenario.POLICY_FAILURE) {
                throw new IllegalStateException("Policy failed");
            }
            return scenario.decision;
        };
    }

    private static PlatformOutboundPropagation propagation(Scenario scenario, AtomicInteger resolveCalls) {
        return (decision, control) -> {
            resolveCalls.incrementAndGet();
            assertThat(decision).isSameAs(scenario.decision);
            InboundTraceControl present = Objects.requireNonNull(control, "control");
            assertThat(present.forceTrace()).isTrue();
            assertThat(present.qaTrace()).isTrue();
            if (scenario == Scenario.RESOLVE_FAILURE) {
                throw new IllegalStateException("Propagation failed");
            }
            return scenario.headers;
        };
    }

    private static PlatformOutboundExchangeFilterFunction filter(List<InboundTraceControl> controls) {
        return new PlatformOutboundExchangeFilterFunction(
                destination -> OutboundPropagationDecision.ALLOW_ALL,
                (decision, control) -> {
                    InboundTraceControl present = Objects.requireNonNull(control, "control");
                    controls.add(present);
                    return new OutboundPropagationHeaders(
                            present.forceTrace() ? Optional.of("on") : Optional.empty(),
                            present.qaTrace() ? Optional.of("1") : Optional.empty(),
                            Optional.empty(), Optional.empty());
                });
    }

    private static void subscribe(Mono<ClientResponse> filtered, boolean forceTrace, boolean qaTrace) {
        filtered.contextWrite(context -> context.put(ReactiveIdentityContextPropagation.KEY,
                        new ReactiveIdentityContextPropagation.IdentityState(null, null, forceTrace, qaTrace)))
                .block(TIMEOUT);
    }

    private static OutboundPropagationHeaders controlHeaders(boolean forceTrace, boolean qaTrace) {
        return new OutboundPropagationHeaders(
                forceTrace ? Optional.of("on") : Optional.empty(),
                qaTrace ? Optional.of("1") : Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static ClientResponse exchange(PlatformOutboundExchangeFilterFunction filter,
                                           ClientRequest request,
                                           List<ClientRequest> exchanged,
                                           ClientResponse response) {
        return filter.filter(request, current -> {
                    exchanged.add(current);
                    return Mono.just(response);
                })
                .contextWrite(context -> context.put(ReactiveIdentityContextPropagation.KEY,
                        new ReactiveIdentityContextPropagation.IdentityState(null, null, true, true)))
                .block(TIMEOUT);
    }

    private static void assertExchanged(ClientRequest current,
                                        ClientRequest request,
                                        OutboundPropagationHeaders headers) {
        assertHeaders(current.headers(), headers);
        assertThat(current.headers().get(HttpHeaders.ACCEPT)).containsExactly("first", "second");
        assertThat(current.url()).isEqualTo(request.url());
        assertThat(current.method()).isEqualTo(request.method());
    }

    private static void assertOriginalRequest(ClientRequest request, boolean preset) {
        PlatformHeaders.OUTBOUND_CONTROL_HEADERS.forEach(name -> {
            if (preset) {
                assertThat(request.headers().get(name)).containsExactly("caller-first", "caller-second");
            } else {
                assertThat(request.headers().containsKey(name)).isFalse();
            }
        });
    }

    private static Stream<Arguments> headerCases() {
        return Arrays.stream(Scenario.values())
                .flatMap(scenario -> Stream.of(Arguments.of(scenario, false), Arguments.of(scenario, true)));
    }

    private static ClientRequest request(boolean preset) {
        ClientRequest.Builder builder = ClientRequest.create(HttpMethod.GET, URI.create("http://orders.internal/items"))
                .header(HttpHeaders.ACCEPT, "first", "second");
        if (preset) {
            builder.headers(headers -> PlatformHeaders.OUTBOUND_CONTROL_HEADERS.forEach(
                    name -> headers.addAll(name.toLowerCase(Locale.ROOT), List.of("caller-first", "caller-second"))));
        }
        return builder.build();
    }

    private static void assertHeaders(HttpHeaders actual, OutboundPropagationHeaders expected) {
        assertThat(actual.get(PlatformHeaders.X_TRACE_ON)).isEqualTo(expected.forceTrace().map(List::of).orElse(null));
        assertThat(actual.get(PlatformHeaders.X_QA_TRACE)).isEqualTo(expected.qaTrace().map(List::of).orElse(null));
        assertThat(actual.get(PlatformHeaders.X_REQUEST_ID)).isEqualTo(expected.requestId().map(List::of).orElse(null));
        assertThat(actual.get(PlatformHeaders.X_CORRELATION_ID)).isEqualTo(expected.correlationId().map(List::of).orElse(null));
    }

    private enum Scenario {
        ALLOW(OutboundPropagationDecision.ALLOW_ALL, ALL_HEADERS),
        PARTIAL_DENY(new OutboundPropagationDecision(false, true, true, true), PARTIAL_HEADERS),
        DENY(OutboundPropagationDecision.DENY_ALL, OutboundPropagationHeaders.EMPTY),
        EMPTY(OutboundPropagationDecision.ALLOW_ALL, OutboundPropagationHeaders.EMPTY),
        POLICY_FAILURE(OutboundPropagationDecision.ALLOW_ALL, OutboundPropagationHeaders.EMPTY),
        RESOLVE_FAILURE(OutboundPropagationDecision.ALLOW_ALL, OutboundPropagationHeaders.EMPTY);

        private final OutboundPropagationDecision decision;
        private final OutboundPropagationHeaders headers;

        Scenario(OutboundPropagationDecision decision, OutboundPropagationHeaders headers) {
            this.decision = decision;
            this.headers = headers;
        }
    }
}
