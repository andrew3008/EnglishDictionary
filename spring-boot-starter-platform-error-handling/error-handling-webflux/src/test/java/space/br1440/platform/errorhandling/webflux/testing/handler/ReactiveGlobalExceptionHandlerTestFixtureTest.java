package space.br1440.platform.errorhandling.webflux.testing.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveGlobalExceptionHandlerTestFixture — smoke")
class ReactiveGlobalExceptionHandlerTestFixtureTest {

    @Test
    @DisplayName("Builder с умолчанями обрабатывает исключение (unknown-путь)")
    void defaultBuilder_handlesUnknown_returnsServerError() {
        // given — пустой список стратегий: срабатывает TestUnknownExceptionHandler
        ReactiveGlobalExceptionHandler handler = ReactiveGlobalExceptionHandlerTestFixture.builder().build();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/fixture-smoke").build());

        // when / then
        StepVerifier.create(handler.handle(exchange, new RuntimeException("smoke")))
                .verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
