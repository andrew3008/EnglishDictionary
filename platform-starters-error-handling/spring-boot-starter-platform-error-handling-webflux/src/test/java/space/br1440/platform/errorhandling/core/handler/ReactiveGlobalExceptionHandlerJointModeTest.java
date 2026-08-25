package space.br1440.platform.errorhandling.core.handler;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ReactiveGlobalExceptionHandler joint mode")
class ReactiveGlobalExceptionHandlerJointModeTest {

    private static final TopExceptionHandlingStrategy STRATEGY = new TopExceptionHandlingStrategy() {
        @Override
        public boolean supports(@NotNull Exception exception) {
            return exception instanceof IllegalStateException;
        }

        @Override
        public @NotNull ErrorEntry handle(Exception exception, @NotNull ErrorHandlingContext context) {
            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .errorCode("FAILED_PRECONDITION")
                    .message(exception.getMessage())
                    .build();
        }

        @Override
        public @NotNull LogPolicy logPolicy(@NotNull Exception exception, @NotNull ErrorEntry errorEntry) {
            return LogPolicy.WARN;
        }

        @Override
        public int getOrder() {
            return 0;
        }
    };

    private static final UnknownExceptionHandler UNKNOWN_HANDLER = new UnknownExceptionHandler() {
        @Override
        public @NotNull ErrorEntry handle(@NotNull Exception exception, @NotNull ErrorHandlingContext context) {
            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("UNKNOWN")
                    .message("Unexpected")
                    .build();
        }

        @Override
        public @NotNull LogPolicy logPolicy(@NotNull Exception exception, @NotNull ErrorEntry errorEntry) {
            return LogPolicy.ERROR;
        }
    };

    private ErrorHandlingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
    }

    private ReactiveGlobalExceptionHandler jointHandler(String effectiveCorrelationId) {
        return ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(STRATEGY))
                .unknownHandler(UNKNOWN_HANDLER)
                .applicationName("joint-test-app")
                .tracingRequestContextSupplier(
                        () -> RequestContext.builder().correlationId(effectiveCorrelationId).build())
                .build();
    }

    @Test
    @DisplayName("effective correlationId читается из tracingRequestContextSupplier, а не из request-заголовка")
    void jointMode_readsFromSupplier_ignoresRequestHeader() {
        // given
        ReactiveGlobalExceptionHandler handler = jointHandler("tracing-owned-corr");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header("X-Correlation-Id", "request-header-value")
                        .build()
        );

        // when
        StepVerifier.create(handler.handle(exchange, new IllegalStateException("bad state")))
                .verifyComplete();

        // then: response echo всегда выключен в joint-режиме
        assertNull(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    @DisplayName("отсутствие активной корреляции (null) не приводит к сбою обработки")
    void jointMode_nullEffectiveCorrelationId_handledGracefully() {
        // given
        ReactiveGlobalExceptionHandler handler = jointHandler(null);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build()
        );

        // when / then
        StepVerifier.create(handler.handle(exchange, new RuntimeException("unknown")))
                .verifyComplete();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertNull(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
    }
}
