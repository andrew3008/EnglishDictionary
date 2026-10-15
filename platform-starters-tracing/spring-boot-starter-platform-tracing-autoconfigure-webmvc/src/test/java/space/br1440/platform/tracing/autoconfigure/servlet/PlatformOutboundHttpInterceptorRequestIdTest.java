package space.br1440.platform.tracing.autoconfigure.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultTraceControlHeaderInjector;
import space.br1440.platform.tracing.runtime.propagation.control.OutboundApplicationIdentitySource;

class PlatformOutboundHttpInterceptorRequestIdTest {

    @Test
    void injectsApplicationRequestIdFromCurrentIdentityBoundary() throws IOException {
        // given
        TracingRuntime runtime = NoOpTracingRuntime.noop();
        RequestIdentityBoundarySupport identityBoundary = new RequestIdentityBoundarySupport(runtime);
        OutboundPropagationPolicy policy = host -> OutboundPropagationDecision.ALLOW_ALL;
        PlatformOutboundHttpInterceptor interceptor = new PlatformOutboundHttpInterceptor(
                policy,
                new DefaultTraceControlHeaderInjector(
                        OutboundApplicationIdentitySource.of(
                                () -> identityBoundary.requestId().orElse(null),
                                () -> null
                        )
                ),
                identityBoundary);
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET,
                URI.create("http://identity-mvc.local/next"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        when(execution.execute(any(HttpRequest.class), any()))
                .thenReturn(response);

        // when
        try (CorrelationScope ignored = runtime.openRequestIdentityScope("app-request", true, true);
             ClientHttpResponse ignoredResponse = interceptor.intercept(request, new byte[0], execution)) {
            // then
            assertThat(ignoredResponse).isSameAs(response);
            assertThat(request.getHeaders().get(PlatformHeaders.X_REQUEST_ID)).containsExactly("app-request");
            assertThat(request.getHeaders().get(PlatformHeaders.X_TRACE_ON)).containsExactly("on");
            assertThat(request.getHeaders().get(PlatformHeaders.X_QA_TRACE)).containsExactly("1");
        }
    }

    @Test
    void doesNotForwardForceTraceOrQaTraceWhenBoundaryStateIsAbsent() throws IOException {
        // given
        TracingRuntime runtime = NoOpTracingRuntime.noop();
        RequestIdentityBoundarySupport identityBoundary = new RequestIdentityBoundarySupport(runtime);
        OutboundPropagationPolicy policy = host -> OutboundPropagationDecision.ALLOW_ALL;
        PlatformOutboundHttpInterceptor interceptor = new PlatformOutboundHttpInterceptor(
                policy,
                new DefaultTraceControlHeaderInjector(
                        OutboundApplicationIdentitySource.of(
                                () -> identityBoundary.requestId().orElse(null),
                                () -> null)),
                identityBoundary);
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET,
                URI.create("http://identity-mvc.local/next"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        when(execution.execute(any(HttpRequest.class), any()))
                .thenReturn(response);

        // when
        try (CorrelationScope ignored = runtime.openRequestIdentityScope("app-request");
             ClientHttpResponse ignoredResponse = interceptor.intercept(request, new byte[0], execution)) {
            // then
            assertThat(ignoredResponse).isSameAs(response);
            assertThat(request.getHeaders().get(PlatformHeaders.X_TRACE_ON)).isNull();
            assertThat(request.getHeaders().get(PlatformHeaders.X_QA_TRACE)).isNull();
        }
    }
}
