package space.br1440.platform.errorhandling.webflux.testing.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;
import space.br1440.platform.errorhandling.webflux.testing.support.strategy.ExtensionSelfTestBadRequestStrategy;
import space.br1440.platform.errorhandling.webflux.testing.support.strategy.ExtensionSelfTestInternalServerErrorStrategy;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveGlobalExceptionHandlerExtension — базовые сценарии")
@ReactiveHandlerTest
class ReactiveGlobalExceptionHandlerExtensionTest {

    @Test
    @ReactiveHandler(strategies = ExtensionSelfTestBadRequestStrategy.class)
    @DisplayName("Конфиг A → HTTP 400")
    void configA_returnsBadRequest(ReactiveGlobalExceptionHandler handler) {
        // given
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ext-a").build());

        // when
        StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

        // then
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @ReactiveHandler(strategies = ExtensionSelfTestInternalServerErrorStrategy.class)
    @DisplayName("Конфиг B → HTTP 500; отличимый код ошибки от конфига A")
    void configB_returnsInternalServerError(ReactiveGlobalExceptionHandler handler) {
        // given
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/ext-b").build());

        // when
        StepVerifier.create(handler.handle(ex, new RuntimeException("y"))).verifyComplete();

        // then
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ex.getResponse().getBodyAsString().block())
                .contains("EXTENSION_TEST_ISE")
                .doesNotContain("EXTENSION_TEST_BR");
    }
}