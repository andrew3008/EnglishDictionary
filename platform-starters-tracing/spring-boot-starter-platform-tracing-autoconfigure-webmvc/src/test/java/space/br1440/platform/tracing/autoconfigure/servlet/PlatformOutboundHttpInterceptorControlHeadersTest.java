package space.br1440.platform.tracing.autoconfigure.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;

class PlatformOutboundHttpInterceptorControlHeadersTest {

    private static final OutboundPropagationHeaders ALL_HEADERS = new OutboundPropagationHeaders(
            Optional.of("on"), Optional.of("1"), Optional.of("platform-request"), Optional.of("platform-correlation"));
    private static final OutboundPropagationHeaders PARTIAL_HEADERS = new OutboundPropagationHeaders(
            Optional.empty(), Optional.of("1"), Optional.of("platform-request"), Optional.of("platform-correlation"));

    @ParameterizedTest
    @MethodSource("outcomes")
    void reserved_headers_match_port_result_and_send_once(boolean preset, Outcome outcome) throws IOException {
        // given
        MockClientHttpRequest request = request(preset);
        byte[] body = {1, 2, 3};
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        OutboundPropagationDecision decision = outcome == Outcome.PARTIAL
                ? new OutboundPropagationDecision(false, true, true, true)
                : outcome == Outcome.DENY ? OutboundPropagationDecision.DENY_ALL : OutboundPropagationDecision.ALLOW_ALL;
        OutboundPropagationHeaders expected = switch (outcome) {
            case ALLOW -> ALL_HEADERS;
            case PARTIAL -> PARTIAL_HEADERS;
            default -> OutboundPropagationHeaders.EMPTY;
        };
        OutboundPropagationPolicy policy = host -> {
            assertThat(host).isEqualTo("control-mvc.local");
            assertReservedHeaders(request.getHeaders(), OutboundPropagationHeaders.EMPTY);
            if (outcome == Outcome.POLICY_FAILURE) {
                throw new IllegalStateException("Policy failed");
            }
            return decision;
        };
        PlatformOutboundPropagation propagation = (actualDecision, control) -> {
            assertThat(actualDecision).isEqualTo(decision);
            assertReservedHeaders(request.getHeaders(), OutboundPropagationHeaders.EMPTY);
            if (outcome == Outcome.RESOLVE_FAILURE) {
                throw new IllegalStateException("Propagation failed");
            }
            return expected;
        };
        PlatformOutboundHttpInterceptor interceptor = interceptor(policy, propagation);

        try (ClientHttpResponse response = mock(ClientHttpResponse.class)) {
            when(execution.execute(request, body)).thenReturn(response);

            // when
            try (ClientHttpResponse actual = interceptor.intercept(request, body, execution)) {
                // then
                assertThat(actual).isSameAs(response);
                assertReservedHeaders(request.getHeaders(), expected);
                assertThat(request.getHeaders().get(HttpHeaders.ACCEPT)).containsExactly("first", "second");
                //noinspection resource — verify() фиксирует вызов execute, возвращаемое значение не является ресурсом
                verify(execution).execute(request, body);
                verifyNoMoreInteractions(execution);
            }
        }
    }

    @Test
    void downstream_runtime_failure_is_propagated_without_second_send() throws IOException {
        // given
        MockClientHttpRequest request = request(true);
        byte[] body = {1};
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        IllegalStateException failure = new IllegalStateException("Transport failed");
        when(execution.execute(request, body)).thenThrow(failure);
        PlatformOutboundHttpInterceptor interceptor = interceptor(
                host -> OutboundPropagationDecision.ALLOW_ALL, (decision, control) -> ALL_HEADERS);

        // when
        // then
        assertThatThrownBy(() -> {
            try (ClientHttpResponse response = interceptor.intercept(request, body, execution)) {
                throw new AssertionError("downstream failure must propagate before a response is returned: " + response);
            }
        }).isSameAs(failure);
        //noinspection resource — verify() фиксирует вызов execute, возвращаемое значение не является ресурсом
        verify(execution).execute(request, body);
        verifyNoMoreInteractions(execution);
        assertReservedHeaders(request.getHeaders(), ALL_HEADERS);
    }

    private static Stream<Arguments> outcomes() {
        return Stream.of(false, true)
                .flatMap(preset -> Stream.of(Outcome.values()).map(outcome -> Arguments.of(preset, outcome)));
    }

    private static PlatformOutboundHttpInterceptor interceptor(OutboundPropagationPolicy policy,
                                                               PlatformOutboundPropagation propagation) {
        return new PlatformOutboundHttpInterceptor(
                policy, propagation, new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop()));
    }

    private static MockClientHttpRequest request(boolean preset) {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://control-mvc.local/next"));
        if (preset) {
            for (String name : PlatformHeaders.OUTBOUND_CONTROL_HEADERS) {
                request.getHeaders().add(name.toLowerCase(Locale.ROOT), "caller-first");
                request.getHeaders().add(name, "caller-second");
            }
        }
        request.getHeaders().addAll(HttpHeaders.ACCEPT, List.of("first", "second"));
        return request;
    }

    private static void assertReservedHeaders(HttpHeaders carrier, OutboundPropagationHeaders expected) {
        assertHeader(carrier, PlatformHeaders.X_TRACE_ON, expected.forceTrace().orElse(null));
        assertHeader(carrier, PlatformHeaders.X_QA_TRACE, expected.qaTrace().orElse(null));
        assertHeader(carrier, PlatformHeaders.X_REQUEST_ID, expected.requestId().orElse(null));
        assertHeader(carrier, PlatformHeaders.X_CORRELATION_ID, expected.correlationId().orElse(null));
    }

    private static void assertHeader(HttpHeaders carrier, String name, @Nullable String value) {
        if (value == null) {
            assertThat(carrier.get(name)).isNull();
        } else {
            assertThat(carrier.get(name)).containsExactly(value);
        }
    }

    private enum Outcome {
        ALLOW, PARTIAL, DENY, EMPTY, POLICY_FAILURE, RESOLVE_FAILURE
    }
}
