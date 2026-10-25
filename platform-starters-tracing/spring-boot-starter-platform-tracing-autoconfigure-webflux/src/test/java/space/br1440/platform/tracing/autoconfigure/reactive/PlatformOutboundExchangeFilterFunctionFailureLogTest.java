package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;

class PlatformOutboundExchangeFilterFunctionFailureLogTest {

    private static final String SECRET = "outbound-propagation-secret";
    private static final String WARN_MESSAGE = "Failed to prepare platform propagation headers: java.lang.IllegalStateException";

    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(PlatformOutboundExchangeFilterFunction.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);

        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }

    @Test
    void logsOnlyFirstFailurePerInstanceAndKeepsOriginalRequestFallback() {
        // given
        OutboundPropagationPolicy failingPolicy = destination -> {
            throw new IllegalStateException(SECRET);
        };
        PlatformOutboundPropagation propagation = mock(PlatformOutboundPropagation.class);
        PlatformOutboundExchangeFilterFunction first =
                new PlatformOutboundExchangeFilterFunction(failingPolicy, propagation);
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://orders.internal/items")).build();
        List<ClientRequest> exchangedRequests = new ArrayList<>();
        ExchangeFunction next = current -> {
            exchangedRequests.add(current);
            return Mono.just(mock(ClientResponse.class));
        };

        // when
        first.filter(request, next).block();
        first.filter(request, next).block();
        PlatformOutboundExchangeFilterFunction second =
                new PlatformOutboundExchangeFilterFunction(failingPolicy, propagation);
        second.filter(request, next).block();

        // then
        assertThat(exchangedRequests)
                .hasSize(3)
                .allSatisfy(exchanged -> assertThat(exchanged).isSameAs(request));

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN, Level.WARN);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(WARN_MESSAGE, WARN_MESSAGE)
                .allSatisfy(message -> assertThat(message).doesNotContain(SECRET));

        assertThat(appender.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
}
