package space.br1440.platform.errorhandling.core.handler;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.extension.*;
import space.br1440.platform.errorhandling.utils.CanonicalErrors;
import space.br1440.platform.errorhandling.http.ErrorMediaTypes;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ReactiveGlobalExceptionHandlerTest {

    private ReactiveGlobalExceptionHandler handler;
    private ErrorHandlingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        TopExceptionHandlingStrategy testStrategy = new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NotNull Exception exception) {
                return exception instanceof IllegalArgumentException;
            }

            @Override
            public @NotNull ErrorEntry handle(Exception exception, @NotNull ErrorHandlingContext context) {
                ErrorInfoDetail info = ErrorInfoDetail.builder()
                        .reason("INVALID_ARGUMENT")
                        .build();

                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.BAD_REQUEST)
                        .errorCode("INVALID_ARGUMENT")
                        .message(exception.getMessage())
                        .details(List.of(info))
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
                ErrorInfoDetail info = ErrorInfoDetail.builder()
                        .reason(CanonicalErrors.UNKNOWN_INTERNAL_ERROR)
                        .build();

                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .errorCode(CanonicalErrors.UNKNOWN_INTERNAL_ERROR)
                        .message("An unexpected error occurred")
                        .details(List.of(info))
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
                .applicationName("test-app")
                .build();
    }

    @Nested
    @DisplayName("Known exception handling")
    class KnownExceptions {

        @Test
        void returns_400_for_IllegalArgumentException() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

            Mono<Void> result = handler.handle(exchange, new IllegalArgumentException("bad input"));

            StepVerifier.create(result).verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
            assertEquals(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE,
                    exchange.getResponse().getHeaders().getContentType());
            assertNotNull(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
        }

        @Test
        void response_body_contains_error_code() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/test").build());

            StepVerifier.create(handler.handle(exchange, new IllegalArgumentException("bad input")))
                    .verifyComplete();

            String body = exchange.getResponse().getBodyAsString()
                    .block();

            assertNotNull(body);
            assertTrue(body.contains("INVALID_ARGUMENT"));
        }
    }

    @Nested
    @DisplayName("Unknown exception handling")
    class UnknownExceptions {

        @Test
        void returns_500_for_unknown_exception() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/test").build());

            StepVerifier.create(handler.handle(exchange, new RuntimeException("unexpected")))
                    .verifyComplete();

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        }
    }

    @Nested
    @DisplayName("Correlation ID propagation")
    class CorrelationId {

        @Test
        void uses_X_Correlation_Id_header_when_present() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/test")
                            .header("X-Correlation-Id", "test-corr-123")
                            .build());

            StepVerifier.create(handler.handle(exchange, new IllegalArgumentException("bad")))
                    .verifyComplete();

            assertEquals("test-corr-123",
                    exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
        }

        @Test
        void generates_UUID_when_no_correlation_header() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/test").build());

            StepVerifier.create(handler.handle(exchange, new IllegalArgumentException("bad")))
                    .verifyComplete();

            String correlationId = exchange.getResponse().getHeaders().getFirst("X-Correlation-Id");
            assertNotNull(correlationId);
            assertFalse(correlationId.isEmpty());
        }
    }

    @Nested
    @DisplayName("Non-Exception Throwable (Sprint 1, H1 — fatal path)")
    class NonExceptionThrowable {

        @Test
        @DisplayName("Error пишет минимальный fatal-ответ 503/UNAVAILABLE без вызова стратегий и enrichers")
        void writesMinimalFatalResponseForError_withoutCallingStrategies() {
            AtomicBoolean strategyCalled = new AtomicBoolean(false);
            AtomicBoolean enricherCalled = new AtomicBoolean(false);

            TopExceptionHandlingStrategy failingStrategy =
                    new TopExceptionHandlingStrategy() {
                        @Override
                        public boolean supports(@NotNull Exception exception) {
                            strategyCalled.set(true);
                            return true;
                        }

                        @Override
                        public @NotNull ErrorEntry handle(@NotNull Exception exception,
                                                          @NotNull ErrorHandlingContext context) {
                            throw new IllegalStateException("strategy must not be called for fatal Error");
                        }

                        @Override
                        public @NotNull LogPolicy logPolicy(@NotNull Exception exception,
                                                            @NotNull ErrorEntry errorEntry) {
                            return LogPolicy.ERROR;
                        }

                        @Override
                        public int getOrder() { return 0; }
                    };

            ErrorEntryEnricher failingEnricher = (entry, ctx) -> {
                        enricherCalled.set(true);
                        throw new IllegalStateException("enricher must not be called for fatal Error");
                    };

            ReactiveGlobalExceptionHandler fatalAwareHandler = ReactiveGlobalExceptionHandlerTestFixture.builder()
                    .properties(properties)
                    .strategies(List.of(failingStrategy))
                    .unknownHandler(new UnknownExceptionHandler() {
                        @Override
                        public @NotNull ErrorEntry handle(@NotNull Exception exception,
                                                          @NotNull ErrorHandlingContext context) {
                            throw new IllegalStateException("unknown handler must not be called for fatal Error");
                        }

                        @Override
                        public @NotNull LogPolicy logPolicy(@NotNull Exception exception,
                                                            @NotNull ErrorEntry errorEntry) {
                            return LogPolicy.ERROR;
                        }
                    })
                    .entryEnrichers(List.of(failingEnricher))
                    .applicationName("test-app")
                    .build();

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/test").build());

            // when
            StepVerifier.create(fatalAwareHandler.handle(exchange, new OutOfMemoryError("oom")))
                    .verifyComplete();

            // then
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
            assertNotNull(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
            assertFalse(strategyCalled.get(), "strategy.supports must not be called on fatal Error");
            assertFalse(enricherCalled.get(), "enricher must not be called on fatal Error");

            String body = exchange.getResponse().getBodyAsString().block();
            assertNotNull(body);

            assertTrue(body.contains("UNAVAILABLE"),
                    "fatal response must use canonical UNAVAILABLE errorCode");
            assertTrue(body.contains("Service temporarily unable"),
                    "fatal response must use generic, allocation-free message");

            assertFalse(body.contains("test-app"),
                    "application name must not leak in fatal response by default");
        }

        @Test
        @DisplayName("StackOverflowError также идёт по fatal-пути (любой не-Exception Throwable)")
        void writesFatalResponseForStackOverflowError() {
            ReactiveGlobalExceptionHandler localHandler = handler;
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/test").build()
            );

            StepVerifier.create(localHandler.handle(exchange, new StackOverflowError("overflow")))
                    .verifyComplete();

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        }
    }
}
