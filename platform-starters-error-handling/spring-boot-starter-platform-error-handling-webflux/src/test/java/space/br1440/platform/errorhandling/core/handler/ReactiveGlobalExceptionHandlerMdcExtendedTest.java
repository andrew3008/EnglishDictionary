package space.br1440.platform.errorhandling.core.handler;

import io.micrometer.context.ContextRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Hooks;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.autoconfigure.MdcThreadLocalAccessor;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveGlobalExceptionHandler MDC — extended")
class ReactiveGlobalExceptionHandlerMdcExtendedTest {

    private ReactiveGlobalExceptionHandler handler;
    private MdcCapturingStrategy capturingStrategy;

    @BeforeEach
    void setUp() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();

        MDC.clear();

        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        capturingStrategy = new MdcCapturingStrategy();

        handler = ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(capturingStrategy))
                .unknownHandler(new TestUnknownHandler())
                .applicationName("mdc-ext-test-app")
                .build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Ключ корреляции (MDC) и exceptionType установлены ДО вызова strategy.handle()")
    void mdcKeys_setPriorToStrategy() {
        // given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/mdc")
                        .header("X-Correlation-Id", "prior-corr-1")
                        .build());

        // when
        StepVerifier.create(handler.handle(exchange, new IllegalArgumentException("test")))
                .verifyComplete();

        // then
        assertThat(capturingStrategy.capturedMdcAtHandle)
                .containsEntry(ErrorHandlingMdcKeys.CORRELATION_ID, "prior-corr-1")
                .containsEntry(ErrorHandlingMdcKeys.EXCEPTION_TYPE, IllegalArgumentException.class.getName());
    }

    @Test
    @DisplayName("error_status, error_code, error_message установлены после enrichMdcFromEntry()")
    void errorKeys_setAfterStrategy() {
        // given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/mdc").build()
        );

        // when
        StepVerifier.create(handler.handle(exchange, new IllegalArgumentException("test")))
                .verifyComplete();

        // then
        assertThat(capturingStrategy.capturedMdcAtHandle)
                .doesNotContainKey("error_status")
                .doesNotContainKey("error_code");
    }

    @Test
    @DisplayName("Внешние MDC-ключи (traceId, spanId) не затираются обработчиком")
    void externalMdcKeys_preserved() {
        // given
        MDC.put("traceId", "trace-abc-123");
        MDC.put("spanId", "span-xyz-456");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/mdc").build()
        );

        // when
        StepVerifier.create(handler.handle(exchange, new IllegalArgumentException("test")))
                .verifyComplete();

        // then
        assertThat(MDC.get("traceId")).isEqualTo("trace-abc-123");
        assertThat(MDC.get("spanId")).isEqualTo("span-xyz-456");
    }

    @Test
    @DisplayName("MDC вызывающего не модифицируется handler'ом (scope-isolation)")
    void callerMdc_notMutated_byHandler() {
        // given
        MDC.put("caller-key", "caller-value");
        MDC.put("error_reason", "set-by-caller-not-by-us");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/mdc").build()
        );

        // when
        StepVerifier.create(handler.handle(exchange, new IllegalArgumentException("test")))
                .verifyComplete();

        // then
        assertThat(MDC.get("caller-key"))
                .as("Внешние ключи вызывающего не должны быть затёрты handler'ом")
                .isEqualTo("caller-value");
        assertThat(MDC.get("error_reason"))
                .as("Значение, установленное вызывающим, не должно быть удалено handler'ом")
                .isEqualTo("set-by-caller-not-by-us");
        assertThat(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID))
                .as("MDC-ключи handler'а не должны протекать наружу")
                .isNull();
        assertThat(MDC.get("error_status"))
                .as("MDC-ключи handler'а не должны протекать наружу")
                .isNull();
    }

    @Test
    @DisplayName("Внутри strategy.handle() error_reason из предыдущего цикла перезаписан (no stale leak)")
    void errorReason_isolatedAcrossInvocations() {
        // given
        MockServerWebExchange exchange1 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/mdc").build()
        );
        StepVerifier.create(handler.handle(exchange1, new IllegalArgumentException("first")))
                .verifyComplete();

        MockServerWebExchange exchange2 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/mdc").build()
        );
        StepVerifier.create(handler.handle(exchange2, new IllegalArgumentException("second")))
                .verifyComplete();

        // then
        assertThat(MDC.get("error_reason")).isNull();
        assertThat(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID)).isNull();
    }

    private static class MdcCapturingStrategy implements TopExceptionHandlingStrategy {

        volatile Map<String, String> capturedMdcAtHandle = Map.of();

        @Override
        public boolean supports(@NonNull Exception exception) {
            return true;
        }

        @Override
        @NonNull
        public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
            Map<String, String> current = MDC.getCopyOfContextMap();
            capturedMdcAtHandle = (current != null) ? new HashMap<>(current) : Map.of();

            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("INVALID_ARGUMENT")
                    .message("Invalid argument")
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
    }

    private static class TestUnknownHandler implements UnknownExceptionHandler {

        @Override
        @NonNull
        public @NotNull ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("UNKNOWN_INTERNAL_ERROR")
                    .message("Unknown error")
                    .build();
        }
    }
}
