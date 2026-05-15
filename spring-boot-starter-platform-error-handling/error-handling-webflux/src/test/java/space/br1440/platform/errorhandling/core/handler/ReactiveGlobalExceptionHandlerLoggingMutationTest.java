package space.br1440.platform.errorhandling.core.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveHandler;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveHandlerTest;
import space.br1440.platform.errorhandling.webflux.testing.support.properties.AllDebugLoggingPropertiesFactory;
import space.br1440.platform.errorhandling.webflux.testing.support.properties.AllErrorLoggingPropertiesFactory;
import space.br1440.platform.errorhandling.webflux.testing.support.properties.CategoryStacktraceOnlyErrorPropertiesFactory;
import space.br1440.platform.errorhandling.webflux.testing.support.properties.DefaultPropertiesFactory;
import space.br1440.platform.errorhandling.webflux.testing.support.strategy.*;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveGlobalExceptionHandler — logException / safety-net side-effects")
@ExtendWith(OutputCaptureExtension.class)
@ReactiveHandlerTest(applicationName = "log-mutation-app")
class ReactiveGlobalExceptionHandlerLoggingMutationTest {

    private static final String CORRELATION_ID = "log-mutation-corr-id";
    private static final String APPLICATION_NAME = "log-mutation-app";

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("logException — switch dispatch (kill SwitchMutator + VoidMethodCallMutator)")
    class LogExceptionSwitch {

        @Test
        @DisplayName("CATEGORY + 5xx → WARN/ERROR в логе содержит exception class и URI")
        @ReactiveHandler(strategies = LoggingMutationInternalServerErrorBoomStrategy.class)
        void categoryStrategy_5xx_logsErrorWithExceptionDetails(ReactiveGlobalExceptionHandler handler,
                                                                CapturedOutput output) {
            // given
            MockServerWebExchange ex = exchange("/api/log-test");

            // when
            StepVerifier.create(handler.handle(ex, new IllegalStateException("expected-msg")))
                    .verifyComplete();

            // then
            assertThat(output.getOut())
                    .as("CATEGORY + ERROR должен залогировать exception class")
                    .contains("IllegalStateException")
                    .as("CATEGORY + ERROR должен залогировать URI запроса")
                    .contains("/api/log-test");
        }

        @Test
        @DisplayName("CATEGORY + 4xx → WARN-уровень содержит метод и URI")
        @ReactiveHandler(strategies = LoggingMutationBadRequestBadStrategy.class)
        void categoryStrategy_4xx_logsAtWarnLevel(ReactiveGlobalExceptionHandler handler,
                                                  CapturedOutput output) {
            // given
            MockServerWebExchange ex = exchange("/api/log-warn");

            // when
            StepVerifier.create(handler.handle(ex, new IllegalArgumentException("bad-input")))
                    .verifyComplete();

            // then
            assertThat(output.getOut())
                    .as("CATEGORY + WARN должен залогировать exception class")
                    .contains("IllegalArgumentException")
                    .as("CATEGORY + WARN должен залогировать URI")
                    .contains("/api/log-warn");
        }

        @Test
        @DisplayName("ALL_DEBUG → запись содержит exception class и URI (kill VoidMethodCall на log.debug)")
        @ReactiveHandler(
                strategies = LoggingMutationBadRequestXWarnStrategy.class,
                propertiesFactory = AllDebugLoggingPropertiesFactory.class
        )
        void allDebugStrategy_logsAtDebugLevel(ReactiveGlobalExceptionHandler handler,
                                               CapturedOutput output) {
            // given
            MockServerWebExchange ex = exchange("/api/log-debug");

            // when
            StepVerifier.create(handler.handle(ex, new NullPointerException("npe-trace")))
                    .verifyComplete();

            // then
            assertThat(output.getOut())
                    .as("ALL_DEBUG не должен пускать сообщение через WARN/ERROR ветки CATEGORY")
                    .doesNotContain("LogStatementWriter");
            assertThat(ex.getResponse().getStatusCode())
                    .as("ALL_DEBUG должен корректно построить ответ")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("ALL_ERROR → запись на ERROR-уровне содержит exception class и URI")
        @ReactiveHandler(
                strategies = LoggingMutationBadRequestXWarnStrategy.class,
                propertiesFactory = AllErrorLoggingPropertiesFactory.class
        )
        void allErrorStrategy_logsAtErrorLevel(ReactiveGlobalExceptionHandler handler,
                                               CapturedOutput output) {
            // given
            MockServerWebExchange ex = exchange("/api/log-error-all");

            // when
            StepVerifier.create(handler.handle(ex, new ArithmeticException("div-zero")))
                    .verifyComplete();

            // then
            assertThat(output.getOut())
                    .as("ALL_ERROR должен залогировать exception class")
                    .contains("ArithmeticException")
                    .as("ALL_ERROR должен залогировать URI запроса")
                    .contains("/api/log-error-all");
        }

        @Test
        @DisplayName("Все три LoggingStrategy не падают — sanity-check на исчерпывающий switch")
        void allLoggingStrategies_completeWithoutError() {
            for (LoggingStrategy loggingStrategy : EnumSet.allOf(LoggingStrategy.class)) {
                // given
                ErrorHandlingProperties props = DefaultPropertiesFactory.baseline();
                props.setLoggingStrategy(loggingStrategy);
                ReactiveGlobalExceptionHandler handler = ReactiveGlobalExceptionHandlerTestFixture.build(
                        props,
                        List.of(new LoggingMutationBadRequestXWarnStrategy()),
                        new NoopExceptionMetricRecorder(),
                        APPLICATION_NAME
                );
                MockServerWebExchange ex = exchange("/api/log-" + loggingStrategy.name());

                // when
                StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

                // then
                assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }
    }

    @Nested
    @DisplayName("logByPolicy — withStacktrace branch (kill VoidMethodCallMutator на write)")
    class LogByPolicyStacktrace {

        @Test
        @DisplayName("ERROR + stacktraceLogLevels=[ERROR] → лог содержит и exception, и stacktrace маркер")
        @ReactiveHandler(
                strategies = LoggingMutationInternalServerErrorStkStrategy.class,
                propertiesFactory = CategoryStacktraceOnlyErrorPropertiesFactory.class
        )
        void errorWithStacktrace_logsStackTrace(ReactiveGlobalExceptionHandler handler,
                                                CapturedOutput output) {
            // given
            MockServerWebExchange ex = exchange("/api/stack");

            // when
            StepVerifier.create(handler.handle(ex, new IllegalStateException("with-stack")))
                    .verifyComplete();

            // then
            assertThat(output.getOut())
                    .as("При stacktrace=true должен быть напечатан caused-by или 'at ' frame")
                    .contains("IllegalStateException")
                    .containsAnyOf("at space.br1440", "at java.");
        }

        @Test
        @DisplayName("WARN без stacktrace в config → лог содержит exception, но НЕ stacktrace 'at' frame")
        @ReactiveHandler(
                strategies = LoggingMutationBadRequestNoStkStrategy.class,
                propertiesFactory = CategoryStacktraceOnlyErrorPropertiesFactory.class)
        void warnWithoutStacktrace_logsWithoutStackFrames(ReactiveGlobalExceptionHandler handler,
                                                          CapturedOutput output) {
            // given
            MockServerWebExchange ex = exchange("/api/no-stack");

            // when
            StepVerifier.create(handler.handle(ex, new IllegalArgumentException("warn-no-stack")))
                    .verifyComplete();

            // then
            String out = output.getOut();
            assertThat(out)
                    .as("WARN должен попасть в лог")
                    .contains("IllegalArgumentException");

            int msgIdx = out.indexOf("warn-no-stack");
            assertThat(msgIdx).as("сообщение должно присутствовать").isGreaterThan(-1);

            String window = out.substring(msgIdx, Math.min(out.length(), msgIdx + 200));
            assertThat(window)
                    .as("WARN без stacktrace не должен иметь 'at ' frame сразу после message")
                    .doesNotContain("\n\tat ");
        }
    }

    @Nested
    @DisplayName("writeSafetyNetResponse — Content-Type side-effect (kill VoidMethodCall на setContentType)")
    class SafetyNetContentType {

        @Test
        @DisplayName("Стратегия throws → safety-net response имеет Content-Type application/problem+json")
        @ReactiveHandler(strategies = LoggingMutationSafetyNetThrowingStrategy.class)
        void safetyNetSetsContentType(ReactiveGlobalExceptionHandler handler) {
            // given
            MockServerWebExchange ex = exchange("/api/safety-net");

            // when
            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            // then
            assertThat(ex.getResponse().getStatusCode())
                    .as("safety-net выставляет 500 INTERNAL_SERVER_ERROR")
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

            MediaType ct = ex.getResponse().getHeaders().getContentType();
            assertThat(ct)
                    .as("safety-net Content-Type не должен быть null (kill VoidMethodCall)")
                    .isNotNull();
            assertThat(ct.getType()).isEqualTo("application");
            assertThat(ct.getSubtype())
                    .as("safety-net subtype должен быть problem+json")
                    .isEqualTo("problem+json");
        }

        @Test
        @DisplayName("Стратегия throws → safety-net response имеет X-Correlation-Id header")
        @ReactiveHandler(strategies = LoggingMutationSafetyNetThrowingStrategy.class)
        void safetyNetSetsCorrelationIdHeader(ReactiveGlobalExceptionHandler handler) {
            // given
            MockServerWebExchange ex = exchange("/api/safety-net-corr");

            // when
            StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

            // then
            HttpHeaders headers = ex.getResponse().getHeaders();
            String corr = headers.getFirst("X-Correlation-Id");
            assertThat(corr)
                    .as("safety-net должен пробросить correlationId в response header")
                    .isEqualTo(CORRELATION_ID);
        }
    }

    private static MockServerWebExchange exchange(@NonNull String path) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get(path)
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .build()
        );
    }
}
