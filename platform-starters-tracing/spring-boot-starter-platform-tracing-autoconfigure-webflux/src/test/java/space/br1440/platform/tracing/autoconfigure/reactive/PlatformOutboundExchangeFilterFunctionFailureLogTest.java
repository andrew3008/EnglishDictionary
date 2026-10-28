package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void logsOnlyFirstFailurePerInstanceAndSendsStrippedRequest(boolean failPolicy) {
        // given
        OutboundPropagationPolicy failingPolicy = destination -> {
            if (!failPolicy) {
                return OutboundPropagationDecision.ALLOW_ALL;
            }

            throw new IllegalStateException(SECRET);
        };

        PlatformOutboundPropagation propagation = (decision, control) -> {
            throw new IllegalStateException(SECRET);
        };

        PlatformOutboundExchangeFilterFunction first = new PlatformOutboundExchangeFilterFunction(failingPolicy, propagation);

        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://orders.internal/items"))
                .headers(headers -> PlatformHeaders.OUTBOUND_CONTROL_HEADERS.forEach(
                        name -> headers.addAll(name.toLowerCase(Locale.ROOT), List.of("caller-first", "caller-second"))))
                .header(HttpHeaders.ACCEPT, "preserved")
                .build();

        List<ClientRequest> exchangedRequests = new ArrayList<>();
        ExchangeFunction next = current -> {
            exchangedRequests.add(current);
            return Mono.just(mock(ClientResponse.class));
        };

        // when
        first.filter(request, next).block(Duration.ofSeconds(5));
        first.filter(request, next).block(Duration.ofSeconds(5));

        PlatformOutboundExchangeFilterFunction second = new PlatformOutboundExchangeFilterFunction(
                failingPolicy,
                propagation
        );
        second.filter(request, next).block(Duration.ofSeconds(5));

        // then
        assertThat(exchangedRequests)
                .hasSize(3)
                .allSatisfy(exchanged -> {
                    PlatformHeaders.OUTBOUND_CONTROL_HEADERS.forEach(
                            name -> assertThat(exchanged.headers().containsKey(name)).isFalse()
                    );

                    assertThat(exchanged.headers().get(HttpHeaders.ACCEPT)).containsExactly("preserved");
                });

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
