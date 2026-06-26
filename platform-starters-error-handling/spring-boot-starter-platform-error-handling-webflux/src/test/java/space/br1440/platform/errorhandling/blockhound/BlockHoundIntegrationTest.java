package space.br1440.platform.errorhandling.blockhound;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BlockHound — интеграция и регрессионные сценарии")
class BlockHoundIntegrationTest {

    @Test
    @DisplayName("Sanity: Thread.sleep на parallel scheduler падает с BlockingOperationError")
    void sanity_threadSleep_isDetected() {
        // given
        Mono<String> blocking = Mono.fromCallable(() -> {
            Thread.sleep(10);
            return "ok";
        }).subscribeOn(Schedulers.parallel());

        // when / then
        assertThatThrownBy(() -> blocking.block(Duration.ofSeconds(5)))
                .as("BlockHound должен детектить Thread.sleep на parallel scheduler")
                .isInstanceOfAny(BlockingOperationError.class, RuntimeException.class)
                .hasStackTraceContaining("Blocking call");
    }

    @Test
    @DisplayName("Production-path: handle() на parallel scheduler — без блокировок")
    void productionPath_isCleanOnParallelScheduler() {
        // given
        ReactiveGlobalExceptionHandler handler = buildHandler();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/blockhound").build()
        );

        // when
        String correlationHeader = handler.handle(exchange, new IllegalStateException("trigger"))
                .subscribeOn(Schedulers.parallel())
                .then(Mono.fromCallable(() -> exchange.getResponse().getHeaders().getFirst("X-Correlation-Id")))
                .block(Duration.ofSeconds(10));

        // then
        assertThat(correlationHeader)
                .as("Должен быть установлен корреляционный header — значит pipeline дошёл до конца")
                .isNotBlank();
    }

    @NonNull
    private static ReactiveGlobalExceptionHandler buildHandler() {
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        TopExceptionHandlingStrategy strategy = new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NonNull Exception exception) {
                return exception instanceof IllegalStateException;
            }

            @Override
            @NonNull
            public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .errorCode("FAILED_PRECONDITION")
                        .message(exception.getMessage())
                        .build();
            }

            @Override
            @NonNull
            public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
                return LogPolicy.WARN;
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };

        UnknownExceptionHandler unknown = new UnknownExceptionHandler() {

            @Override
            @NonNull
            public @NotNull ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .errorCode("UNKNOWN")
                        .message("unknown")
                        .build();
            }
        };

        // Сборка через тестовую фикстуру — те же дефолты, что при ручной конструкции конфигурации.
        return ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(strategy))
                .unknownHandler(unknown)
                .applicationName("blockhound-test-app")
                .build();
    }
}
