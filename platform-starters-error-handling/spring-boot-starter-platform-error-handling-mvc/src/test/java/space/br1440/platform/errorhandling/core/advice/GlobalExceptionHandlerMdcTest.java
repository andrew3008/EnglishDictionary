package space.br1440.platform.errorhandling.core.advice;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.mock.web.MockHttpServletRequest;
import space.br1440.platform.errorhandling.utils.CanonicalErrors;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.mvc.internal.ExceptionLogger;
import space.br1440.platform.errorhandling.mvc.internal.ProblemResponseFactory;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerMdcTest {

    private static final String CORRELATION_ID = "test-corr-123";
    private static final String APP_NAME = "test-service";
    private static final String REQUEST_URI = "/api/test";

    private static final String[] MDC_KEYS = {
            ErrorHandlingMdcKeys.CORRELATION_ID, "exceptionType", "error_status",
            "error_code", "error_message"
    };

    private GlobalExceptionHandler handler;
    private MdcCapturingStrategy capturingStrategy;

    @BeforeEach
    void setUp() {
        MDC.clear();

        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        capturingStrategy = new MdcCapturingStrategy();
        ExceptionMetricRecorder metrics = new NoopExceptionMetricRecorder();
        ErrorEntryToDtoMapper mapper = newMapper();

        UnknownExceptionHandler unknownHandler = new TestUnknownExceptionHandler();

        handler = new GlobalExceptionHandler(
                properties,
                List.of(capturingStrategy),
                unknownHandler,
                metrics,
                new ProblemResponseFactory(mapper, APP_NAME, properties),
                new ExceptionLogger(properties),
                space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver.defaultInstance(),
                Collections.emptyList(),
                new StackHintExtractor(false, List.of()),
                Collections.emptyList(),
                null
        );
    }

    private static ErrorEntryToDtoMapper newMapper() {
        return new ErrorEntryToDtoMapper();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("MDC lifecycle")
    class MdcLifecycle {

        @Test
        @DisplayName("MDC-ключи очищаются после обработки исключения")
        void mdcKeys_clearedAfterHandling() {
            // when
            handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            for (String key : MDC_KEYS) {
                assertNull(MDC.get(key),
                        "MDC key '" + key + "' should be cleared after handling");
            }
        }

        @Test
        @DisplayName("MDC-ключи очищаются даже при ошибке в обработчике")
        void mdcKeys_clearedEvenOnHandlerFailure() {
            // given
            ErrorHandlingProperties properties = new ErrorHandlingProperties();
            properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

            TopExceptionHandlingStrategy failingStrategy = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception exception) { return true; }
                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
                    throw new RuntimeException("handler failure");
                }
                @Override
                public int getOrder() { return 0; }
            };

            GlobalExceptionHandler failingHandler = new GlobalExceptionHandler(
                    properties,
                    List.of(failingStrategy),
                    new TestUnknownExceptionHandler(),
                    new NoopExceptionMetricRecorder(),
                    new ProblemResponseFactory(newMapper(), APP_NAME, properties),
                    new ExceptionLogger(properties),
                    space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver.defaultInstance(),
                    Collections.emptyList(),
                    new StackHintExtractor(false, List.of()),
                    Collections.emptyList(),
                    null
            );

            // when
            failingHandler.handleException(new RuntimeException("original"), mockRequest());

            // then
            for (String key : MDC_KEYS) {
                assertNull(MDC.get(key),"MDC key '" + key + "' should be cleared even after handler failure");
            }
        }

        @Test
        @DisplayName("error_reason очищается, даже если entry не содержит reason")
        void errorReason_clearedWhenNotSet() {
            // given
            MDC.put("error_reason", "pre-existing-value");

            // when
            handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            assertNull(MDC.get("error_reason"),"error_reason should be cleared even if not set by entry");
        }
    }

    @Nested
    @DisplayName("MDC content contract")
    class MdcContent {

        @Test
        @DisplayName("correlationId устанавливается до вызова стратегии")
        void correlationId_setPriorToStrategy() {
            // when
            handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            assertEquals(CORRELATION_ID, capturingStrategy.capturedMdcAtHandle.get(ErrorHandlingMdcKeys.CORRELATION_ID));
        }

        @Test
        @DisplayName("exceptionType устанавливается до вызова стратегии")
        void exceptionType_setPriorToStrategy() {
            // when
            handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            assertEquals("java.lang.IllegalArgumentException",
                    capturingStrategy.capturedMdcAtHandle.get("exceptionType"));
        }

        @Test
        @DisplayName("error_status, error_code, error_message устанавливаются из ErrorEntry")
        void enrichedKeys_setFromErrorEntry() {
            // given
            final Map<String, String> capturedAtConversion = new HashMap<>();
            ErrorEntryToDtoMapper spyMapper = Mockito.spy(newMapper());
            Mockito.doAnswer(invocation -> {
                capturedAtConversion.putAll(MDC.getCopyOfContextMap());
                return invocation.callRealMethod();
            }).when(spyMapper).toDto(Mockito.any(ErrorEntry.class));

            ErrorHandlingProperties props = new ErrorHandlingProperties();
            props.setLoggingStrategy(LoggingStrategy.CATEGORY);

            GlobalExceptionHandler handlerWithCapture = new GlobalExceptionHandler(
                    props,
                    List.of(capturingStrategy),
                    new TestUnknownExceptionHandler(),
                    new NoopExceptionMetricRecorder(),
                    new ProblemResponseFactory(spyMapper, APP_NAME, props),
                    new ExceptionLogger(props),
                    space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver.defaultInstance(),
                    Collections.emptyList(),
                    new StackHintExtractor(false, List.of()),
                    Collections.emptyList(),
                    null
            );

            // when
            handlerWithCapture.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            assertEquals("400", capturedAtConversion.get("error_status"));
            assertEquals("INVALID_ARGUMENT", capturedAtConversion.get("error_code"));
            assertEquals("Invalid argument", capturedAtConversion.get("error_message"));
        }

        @Test
        @DisplayName("correlationId и exceptionType — обязательные, присутствуют всегда")
        void mandatoryKeys_alwaysPresent() {
            // when
            handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            Map<String, String> captured = capturingStrategy.capturedMdcAtHandle;
            assertNotNull(captured.get(ErrorHandlingMdcKeys.CORRELATION_ID), "correlationId must be present");
            assertNotNull(captured.get("exceptionType"), "exceptionType must be present");
        }
    }

    @Nested
    @DisplayName("Tracing keys isolation")
    class TracingIsolation {

        @Test
        @DisplayName("traceId не изменяется обработчиком")
        void traceId_notModified() {
            // given
            String originalTraceId = "abc-trace-123";
            MDC.put("traceId", originalTraceId);

            // when
            handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            assertEquals(originalTraceId, MDC.get("traceId"),
                    "traceId must not be modified by handler");
        }

        @Test
        @DisplayName("spanId не изменяется обработчиком")
        void spanId_notModified() {
            // given
            String originalSpanId = "xyz-span-456";
            MDC.put("spanId", originalSpanId);

            // when
            handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            assertEquals(originalSpanId, MDC.get("spanId"),
                    "spanId must not be modified by handler");
        }

        @Test
        @DisplayName("Внешние MDC-ключи не затираются обработчиком")
        void externalMdcKeys_preserved() {
            // given
            MDC.put("userId", "user-42");
            MDC.put("tenantId", "tenant-7");

            // when
            handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            assertEquals("user-42", MDC.get("userId"));
            assertEquals("tenant-7", MDC.get("tenantId"));
        }
    }

    @Nested
    @DisplayName("Response contract")
    class ResponseContract {

        @Test
        @DisplayName("Ответ содержит X-Correlation-Id заголовок")
        void response_containsCorrelationIdHeader() {
            // when
            ResponseEntity<ErrorEntryDTO> response =
                    handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            assertEquals(CORRELATION_ID,
                    response.getHeaders().getFirst("X-Correlation-Id"));
        }

        @Test
        @DisplayName("Ответ содержит корректный HTTP-статус")
        void response_containsCorrectStatus() {
            // when
            ResponseEntity<ErrorEntryDTO> response =
                    handler.handleException(new IllegalArgumentException("test"), mockRequest());

            // then
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("UnknownExceptionHandler fallback")
    class UnknownHandlerFallback {

        @Test
        @DisplayName("Неклассифицированное исключение обрабатывается UnknownExceptionHandler")
        void unknownException_handledByFallback() {
            // given
            ErrorHandlingProperties properties = new ErrorHandlingProperties();
            properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
            MdcCapturingUnknownHandler capturingUnknown = new MdcCapturingUnknownHandler();
            GlobalExceptionHandler handlerNoStrategies = new GlobalExceptionHandler(
                    properties,
                    Collections.emptyList(),
                    capturingUnknown,
                    new NoopExceptionMetricRecorder(),
                    new ProblemResponseFactory(newMapper(), APP_NAME, properties),
                    new ExceptionLogger(properties),
                    space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver.defaultInstance(),
                    Collections.emptyList(),
                    new StackHintExtractor(false, List.of()),
                    Collections.emptyList(),
                    null
            );

            // when
            handlerNoStrategies.handleException(
                    new UnsupportedOperationException("unknown op"), mockRequest());

            // then
            assertNotNull(capturingUnknown.capturedMdc.get(ErrorHandlingMdcKeys.CORRELATION_ID));
            assertEquals("java.lang.UnsupportedOperationException",
                    capturingUnknown.capturedMdc.get("exceptionType"));
        }

        @Test
        @DisplayName("MDC-ключи очищаются после fallback-обработки")
        void mdcKeys_clearedAfterFallback() {
            // given
            ErrorHandlingProperties properties = new ErrorHandlingProperties();
            properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
            GlobalExceptionHandler handlerNoStrategies = new GlobalExceptionHandler(
                    properties,
                    Collections.emptyList(),
                    new TestUnknownExceptionHandler(),
                    new NoopExceptionMetricRecorder(),
                    new ProblemResponseFactory(newMapper(), APP_NAME, properties),
                    new ExceptionLogger(properties),
                    space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver.defaultInstance(),
                    Collections.emptyList(),
                    new StackHintExtractor(false, List.of()),
                    Collections.emptyList(),
                    null
            );

            // when
            handlerNoStrategies.handleException(
                    new UnsupportedOperationException("test"), mockRequest());

            // then
            for (String key : MDC_KEYS) {
                assertNull(MDC.get(key),
                        "MDC key '" + key + "' should be cleared after fallback handling");
            }
        }
    }

    private static MockHttpServletRequest mockRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", REQUEST_URI);
        request.addHeader("X-Correlation-Id", CORRELATION_ID);
        return request;
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
            capturedMdcAtHandle = new HashMap<>(MDC.getCopyOfContextMap());

            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("INVALID_ARGUMENT")
                    .message("Invalid argument")
                    .requestURI(context.getRequestPath())
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

    private static class TestUnknownExceptionHandler implements UnknownExceptionHandler {
        @Override
        @NonNull
        public @NotNull ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
                    return ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode(CanonicalErrors.UNKNOWN_INTERNAL_ERROR)
                    .message("Unknown error")
                    .build();
        }
    }

    private static class MdcCapturingUnknownHandler implements UnknownExceptionHandler {

        volatile Map<String, String> capturedMdc = Map.of();

        @Override
        @NonNull
        public @NotNull ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
            capturedMdc = new HashMap<>(MDC.getCopyOfContextMap());

            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode(CanonicalErrors.UNKNOWN_INTERNAL_ERROR)
                    .message("Unknown error")
                    .build();
        }
    }
}
