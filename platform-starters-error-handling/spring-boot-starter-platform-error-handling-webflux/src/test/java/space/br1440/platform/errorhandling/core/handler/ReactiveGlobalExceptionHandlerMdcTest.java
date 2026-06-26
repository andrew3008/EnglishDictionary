package space.br1440.platform.errorhandling.core.handler;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReactiveGlobalExceptionHandler MDC propagation")
class ReactiveGlobalExceptionHandlerMdcTest {

    private ReactiveGlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        MDC.clear();

        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        TopExceptionHandlingStrategy testStrategy = new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NotNull Exception exception) {
                return (exception instanceof IllegalStateException);
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
                return 100;
            }
        };

        UnknownExceptionHandler unknownHandler = new UnknownExceptionHandler() {
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

        handler = ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(testStrategy))
                .unknownHandler(unknownHandler)
                .applicationName("mdc-test-app")
                .build();
    }

    @Test
    @DisplayName("MDC is cleared after exception handling completes")
    void mdc_is_clean_after_handling() {
        // given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header("X-Correlation-Id", "mdc-test-123")
                        .build()
        );

        // when
        StepVerifier.create(handler.handle(exchange, new IllegalStateException("bad state")))
                .verifyComplete();

        // then
        assertNull(MDC.get("correlationId"), "correlationId should be cleared from MDC after handling");
        assertNull(MDC.get("exceptionType"), "exceptionType should be cleared from MDC after handling");
        assertNull(MDC.get("error_status"), "error_status should be cleared from MDC after handling");
        assertNull(MDC.get("error_code"), "error_code should be cleared from MDC after handling");
        assertNull(MDC.get("error_message"), "error_message should be cleared from MDC after handling");
    }

    @Test
    @DisplayName("MDC is cleared even when unknown exception is handled")
    void mdc_is_clean_after_unknown_exception() {
        // given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build()
        );

        // when
        StepVerifier.create(handler.handle(exchange, new RuntimeException("unknown")))
                .verifyComplete();

        // then
        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("exceptionType"));
    }

    @Test
    @DisplayName("MDC is not polluted between sequential requests")
    void mdc_isolation_between_requests() {
        // given
        MockServerWebExchange exchange1 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/first")
                        .header("X-Correlation-Id", "corr-1")
                        .build()
        );

        // when
        StepVerifier.create(handler.handle(exchange1, new IllegalStateException("first")))
                .verifyComplete();

        // then
        assertNull(MDC.get("correlationId"), "MDC should be clean between requests");

        // given
        MockServerWebExchange exchange2 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/second")
                        .header("X-Correlation-Id", "corr-2")
                        .build());
        // when
        StepVerifier.create(handler.handle(exchange2, new IllegalStateException("second")))
                .verifyComplete();

        // then
        assertNull(MDC.get("correlationId"), "MDC should be clean after second request");

        assertEquals("corr-2",
                exchange2.getResponse().getHeaders().getFirst("X-Correlation-Id"));
    }
}
