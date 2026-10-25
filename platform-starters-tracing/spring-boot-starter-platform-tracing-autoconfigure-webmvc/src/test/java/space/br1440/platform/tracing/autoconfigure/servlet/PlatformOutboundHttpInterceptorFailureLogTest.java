package space.br1440.platform.tracing.autoconfigure.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class PlatformOutboundHttpInterceptorFailureLogTest {

    private static final String FAILURE_MESSAGE = "outbound-secret-message";
    private static final String WARN_MESSAGE = "Failed to prepare platform propagation headers: java.lang.IllegalStateException";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger(PlatformOutboundHttpInterceptor.class);
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void propagationFailureWarnIsBoundedPerInstanceAndExecutionContinues() throws Exception {
        // given
        OutboundPropagationPolicy failingPolicy = host -> {
            throw new IllegalStateException(FAILURE_MESSAGE);
        };
        PlatformOutboundPropagation propagation = mock(PlatformOutboundPropagation.class);
        RequestIdentityBoundarySupport identityBoundary =
                new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop());
        PlatformOutboundHttpInterceptor first = new PlatformOutboundHttpInterceptor(
                failingPolicy,
                propagation,
                identityBoundary
        );
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET,
                URI.create("http://bounded-warning.local/next")
        );
        byte[] body = new byte[0];
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(execution.execute(any(HttpRequest.class), any(byte[].class))).thenReturn(response);

        // when
        ClientHttpResponse firstResult = first.intercept(request, body, execution);
        ClientHttpResponse repeatedResult = first.intercept(request, body, execution);

        // then
        assertThat(appender.list).hasSize(1);

        // when
        PlatformOutboundHttpInterceptor second = new PlatformOutboundHttpInterceptor(
                failingPolicy,
                propagation,
                identityBoundary
        );
        ClientHttpResponse secondResult = second.intercept(request, body, execution);

        // then
        assertThat(firstResult).isSameAs(response);
        assertThat(repeatedResult).isSameAs(response);
        assertThat(secondResult).isSameAs(response);
        verify(execution, times(3)).execute(request, body);
        assertThat(appender.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(WARN_MESSAGE);
            assertThat(event.getFormattedMessage()).doesNotContain(FAILURE_MESSAGE);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
