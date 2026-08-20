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
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;

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
                        PlatformHeaders.X_TRACE_ON,
                        PlatformHeaders.X_QA_TRACE,
                        PlatformHeaders.X_REQUEST_ID,
                        OutboundApplicationIdentitySource.of(
                                () -> identityBoundary.requestId().orElse(null),
                                () -> null)),
                identityBoundary);
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET,
                URI.create("http://identity-mvc.local/next"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(HttpRequest.class), any()))
                .thenReturn(null);

        // when
        try (CorrelationScope ignored = runtime.openRequestIdentityScope("app-request", true, true)) {
            interceptor.intercept(request, new byte[0], execution);
        }

        // then
        assertThat(request.getHeaders().get(PlatformHeaders.X_REQUEST_ID)).containsExactly("app-request");
        assertThat(request.getHeaders().get(PlatformHeaders.X_TRACE_ON)).containsExactly("on");
        assertThat(request.getHeaders().get(PlatformHeaders.X_QA_TRACE)).containsExactly("1");
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
                        PlatformHeaders.X_TRACE_ON,
                        PlatformHeaders.X_QA_TRACE,
                        PlatformHeaders.X_REQUEST_ID,
                        OutboundApplicationIdentitySource.of(
                                () -> identityBoundary.requestId().orElse(null),
                                () -> null)),
                identityBoundary);
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET,
                URI.create("http://identity-mvc.local/next"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(HttpRequest.class), any()))
                .thenReturn(null);

        // when
        try (CorrelationScope ignored = runtime.openRequestIdentityScope("app-request")) {
            interceptor.intercept(request, new byte[0], execution);
        }

        // then
        assertThat(request.getHeaders().get(PlatformHeaders.X_TRACE_ON)).isNull();
        assertThat(request.getHeaders().get(PlatformHeaders.X_QA_TRACE)).isNull();
    }
}
