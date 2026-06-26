package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.MapBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpringMvcExceptionStrategy")
class SpringMvcExceptionStrategyTest {

    private SpringMvcExceptionStrategy strategy;
    private ErrorHandlingContext context;

    @BeforeEach
    void setUp() {
        CauseChainResolver causeChainResolver = new CauseChainResolver(Collections.emptyList());
        strategy = new SpringMvcExceptionStrategy(causeChainResolver);

        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        context = ErrorHandlingContext.of(properties, "/api/test", new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("supports() контракт")
    class SupportsContract {

        @ParameterizedTest(name = "{0}")
        @MethodSource("space.br1440.platform.errorhandling.core.strategy.top.SpringMvcExceptionStrategyTestArguments#supportedExceptionProvider")
        @DisplayName("supports() возвращает true для поддерживаемых типов")
        void supports_returnsTrue_forSupportedTypes(String name, Exception exception) {
            // when / then
            assertThat(strategy.supports(exception)).isTrue();
        }

        @Test
        @DisplayName("supports() возвращает false для неподдерживаемых исключений")
        void supports_returnsFalse_forUnsupportedTypes() {
            // when / then
            assertThat(strategy.supports(new IllegalArgumentException("test"))).isFalse();
            assertThat(strategy.supports(new NullPointerException("test"))).isFalse();
            assertThat(strategy.supports(new RuntimeException("test"))).isFalse();
        }
    }

    @Nested
    @DisplayName("HTTP-статус маппинг")
    class HttpStatusMapping {

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("space.br1440.platform.errorhandling.core.strategy.top.SpringMvcExceptionStrategyTestArguments#httpStatusMappingProvider")
        @DisplayName("Исключение маппится в ожидаемый HTTP-статус")
        void exception_mapsToExpectedStatus(String scenarioName, HttpStatus expectedStatus, Exception exception) {
            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(expectedStatus);
        }
    }

    @Nested
    @DisplayName("errorCode маппинг")
    class ErrorCodeMapping {

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("space.br1440.platform.errorhandling.core.strategy.top.SpringMvcExceptionStrategyTestArguments#errorCodeMappingProvider")
        @DisplayName("Исключение маппится в ожидаемый errorCode")
        void exception_mapsToExpectedErrorCode(String scenarioName, String expectedCode, Exception exception) {
            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getErrorCode()).isEqualTo(expectedCode);
        }
    }

    @Nested
    @DisplayName("Содержимое message/reason")
    class MessageContent {

        @Test
        @DisplayName("MissingServletRequestParameterException содержит имя параметра в message")
        void missingParameter_containsParameterName() {
            // given
            var exception = new MissingServletRequestParameterException("userId", "String");

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getMessage()).contains("userId").contains("String");
            assertThat(entry.getReason()).isEqualTo("Required parameter is missing");
        }

        @Test
        @DisplayName("NoHandlerFoundException содержит метод и URL в message")
        void noHandler_containsMethodAndUrl() {
            // given
            var exception = new NoHandlerFoundException("POST", "/api/unknown", HttpHeaders.EMPTY);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getMessage()).contains("POST").contains("/api/unknown");
            assertThat(entry.getReason()).isEqualTo("Resource not found");
        }

        @Test
        @DisplayName("HttpRequestMethodNotSupportedException содержит имя метода")
        void methodNotSupported_containsMethodName() {
            // given
            var exception = new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "POST"));

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getMessage()).contains("PATCH");
            assertThat(entry.getReason()).isEqualTo("HTTP method not supported");
        }

        @Test
        @DisplayName("MaxUploadSizeExceededException содержит лимит размера")
        void maxUploadSize_containsSizeLimit() {
            // given
            var exception = new MaxUploadSizeExceededException(1048576L);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getMessage()).contains("1048576");
            assertThat(entry.getReason()).isEqualTo("Payload too large");
        }

        @Test
        @DisplayName("CWE-22: path-traversal в URL заменяется маркером, не утекает в response")
        void pathTraversal_replacedWithMarker() {
            // given
            var exception = new NoHandlerFoundException("GET", "/api/v1/../../../etc/passwd", HttpHeaders.EMPTY);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getMessage())
                    .doesNotContain("..")
                    .doesNotContain("etc/passwd")
                    .contains("<invalid-path>");
            assertThat(entry.getReason()).isEqualTo("Resource not found");
        }

        @Test
        @DisplayName("CWE-22: URL-encoded path-traversal (%2E%2E) тоже отклоняется")
        void urlEncodedPathTraversal_replacedWithMarker() {
            // given
            var exception = new NoHandlerFoundException("GET", "/api/%2E%2E/%2E%2E/etc/passwd", HttpHeaders.EMPTY);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getMessage())
                    .doesNotContain("%2E")
                    .doesNotContain("%2e")
                    .contains("<invalid-path>");
        }

        @Test
        @DisplayName("CWE-117: CR/LF в имени параметра не ломают строчность лога")
        void crlfInParameterName_stripped() {
            // given
            var exception = new MissingServletRequestParameterException("user\r\nINJECTED-LINE", "String");

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getMessage())
                    .doesNotContain("\r")
                    .doesNotContain("\n");
        }

        @Test
        @DisplayName("URL с числовыми/UUID-сегментами нормализуется до {id}")
        void urlWithDynamicSegments_normalized() {
            // given
            var exception = new NoHandlerFoundException("GET", "/api/users/42/orders/abc-uuid-1234", HttpHeaders.EMPTY);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getMessage())
                    .contains("/api/users/{id}/orders")
                    .doesNotContain("42");
        }
    }

    @Nested
    @DisplayName("ErrorDetails с clientMetadata")
    class ErrorDetails {

        @Test
        @DisplayName("MissingServletRequestParameterException содержит parameterName/parameterType в metadata")
        void missingParameter_hasMetadataKeys() {
            // given
            var exception = new MissingServletRequestParameterException("userId", "Long");

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getDetails()).isNotEmpty();
            ErrorInfoDetail detail = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(detail.getClientMetadata())
                    .containsEntry("parameterName", "userId")
                    .containsEntry("parameterType", "Long");
        }

        @Test
        @DisplayName("MissingServletRequestPartException содержит partName в metadata")
        void missingPart_hasPartNameInMetadata() {
            // given
            var exception = new MissingServletRequestPartException("file");

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getDetails()).isNotEmpty();
            ErrorInfoDetail detail = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(detail.getClientMetadata()).containsEntry("partName", "file");
        }

        @Test
        @DisplayName("HttpMessageNotReadableException -> header c пустой clientMetadata + granular reason")
        void messageNotReadable_emptyClientMetadataButGranularPresent() {
            // given
            var exception = new HttpMessageNotReadableException(
                    "bad body",
                    SpringMvcExceptionStrategyTestArguments.emptyHttpInputMessage()
            );

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getDetails()).hasSize(2);
            ErrorInfoDetail header = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(header.getClientMetadata()).isEmpty();
            ErrorInfoDetail granular = (ErrorInfoDetail) entry.getDetails().get(1);
            assertThat(granular.getReason()).isEqualTo("MALFORMED_REQUEST_BODY");
        }

        @Test
        @DisplayName("requestURI заполняется из контекста")
        void requestUri_filledFromContext() {
            // given
            var exception = new MissingServletRequestParameterException("id", "String");

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getRequestURI()).isEqualTo("/api/test");
        }
    }

    @Nested
    @DisplayName("logPolicy контракт")
    class LogPolicyContract {

        @Test
        @DisplayName("Routing-исключения (404, 405, 406, 415) -> DEBUG")
        void routingExceptions_logAtDebug() {
            // when / then
            assertLogPolicy(new NoHandlerFoundException("GET", "/test", HttpHeaders.EMPTY), LogPolicy.DEBUG);
            assertLogPolicy(new NoResourceFoundException(HttpMethod.GET, "/test"), LogPolicy.DEBUG);
            assertLogPolicy(new HttpRequestMethodNotSupportedException("PATCH"), LogPolicy.DEBUG);
            assertLogPolicy(new HttpMediaTypeNotSupportedException("unsupported"), LogPolicy.DEBUG);
            assertLogPolicy(new HttpMediaTypeNotAcceptableException("not acceptable"), LogPolicy.DEBUG);
        }

        @Test
        @DisplayName("4xx клиентские ошибки -> WARN")
        void clientErrors_logAtWarn() {
            // given
            var exception = new MissingServletRequestParameterException("id", "String");

            // when
            ErrorEntry entry = strategy.handle(exception, context);
            LogPolicy policy = strategy.logPolicy(exception, entry);

            // then
            assertThat(policy).isEqualTo(LogPolicy.WARN);
        }

        @Test
        @DisplayName("5xx серверные ошибки -> ERROR")
        void serverErrors_logAtError() {
            // given
            var exception = new ConversionNotSupportedException("value", Integer.class, null);

            // when
            ErrorEntry entry = strategy.handle(exception, context);
            LogPolicy policy = strategy.logPolicy(exception, entry);

            // then
            assertThat(policy).isEqualTo(LogPolicy.ERROR);
        }

        @SuppressWarnings("SameParameterValue")
        private void assertLogPolicy(Exception exception, LogPolicy expectedPolicy) {
            ErrorEntry entry = strategy.handle(exception, context);
            LogPolicy policy = strategy.logPolicy(exception, entry);
            assertThat(policy)
                    .as("logPolicy for %s", exception.getClass().getSimpleName())
                    .isEqualTo(expectedPolicy);
        }
    }

    @Nested
    @DisplayName("ErrorResponse fallback")
    class ErrorResponseFallback {

        @Test
        @DisplayName("Исключение, реализующее ErrorResponse, маппится по statusCode")
        void errorResponse_mapsToItsStatusCode() {
            // given
            var exception = new NoResourceFoundException(HttpMethod.GET, "/nonexistent");

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(strategy.supports(exception)).isTrue();
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Spring 6+ ErrorResponseException обрабатывается через ErrorResponse-fallback")
        void errorResponseException_handled() {
            // given
            var exception = new org.springframework.web.ErrorResponseException(
                    HttpStatus.UNPROCESSABLE_ENTITY);

            // when
            assertThat(strategy.supports(exception)).isTrue();
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(entry.getErrorCode()).isEqualTo("INVALID_ARGUMENT"); // canon для 4xx
        }

        @Test
        @DisplayName("Произвольный 5xx ErrorResponseException -> top-level INTERNAL")
        void errorResponseException_5xx_internal() {
            // given
            var exception = new org.springframework.web.ErrorResponseException(
                    HttpStatus.SERVICE_UNAVAILABLE);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(entry.getErrorCode()).isEqualTo("UNAVAILABLE");
        }
    }

    @Nested
    @DisplayName("Granular reason во втором ErrorInfoDetail (AIP-193 details[])")
    class GranularReasonInDetails {

        @Test
        @DisplayName("HttpRequestMethodNotSupported -> top INVALID_ARGUMENT, granular METHOD_NOT_ALLOWED во втором detail")
        void methodNotAllowed_hasGranularReasonInSecondDetail() {
            // given
            var exception = new HttpRequestMethodNotSupportedException("PATCH", List.of("GET"));

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getErrorCode()).isEqualTo("INVALID_ARGUMENT");
            assertThat(entry.getDetails()).hasSize(2);
            ErrorInfoDetail second = (ErrorInfoDetail) entry.getDetails().get(1);
            assertThat(second.getReason()).isEqualTo("METHOD_NOT_ALLOWED");
        }

        @Test
        @DisplayName("HttpMediaTypeNotSupported -> top INVALID_ARGUMENT, granular UNSUPPORTED_MEDIA_TYPE")
        void unsupportedMediaType_hasGranularReason() {
            // given
            var exception = new HttpMediaTypeNotSupportedException(MediaType.TEXT_XML, List.of(MediaType.APPLICATION_JSON));

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getErrorCode()).isEqualTo("INVALID_ARGUMENT");
            assertThat(entry.getDetails()).hasSize(2);
            ErrorInfoDetail second = (ErrorInfoDetail) entry.getDetails().get(1);
            assertThat(second.getReason()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        }

        @Test
        @DisplayName("MaxUploadSize -> top RESOURCE_EXHAUSTED, granular PAYLOAD_TOO_LARGE")
        void payloadTooLarge_hasGranularReason() {
            // given
            var exception = new MaxUploadSizeExceededException(1024L);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getErrorCode()).isEqualTo("RESOURCE_EXHAUSTED");
            assertThat(entry.getDetails()).hasSize(2);
            ErrorInfoDetail second = (ErrorInfoDetail) entry.getDetails().get(1);
            assertThat(second.getReason()).isEqualTo("PAYLOAD_TOO_LARGE");
        }

        @Test
        @DisplayName("NoHandlerFound -> top NOT_FOUND, granular reason не нужен (canon уже NOT_FOUND)")
        void notFound_noGranularReason() {
            // given
            var exception = new NoHandlerFoundException("GET", "/x", HttpHeaders.EMPTY);

            // when
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getErrorCode()).isEqualTo("NOT_FOUND");
            assertThat(entry.getDetails()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Phase 3.1: расширенное покрытие типов")
    class ExtendedTypeSupport {

        @Test
        @DisplayName("MethodArgumentNotValidException -> 400 INVALID_ARGUMENT + violationCount в metadata")
        void methodArgumentNotValid_handled() throws Exception {
            // given
            MapBindingResult bindingResult = new MapBindingResult(new HashMap<>(), "target");
            bindingResult.addError(new ObjectError("target", "code1"));
            bindingResult.addError(new ObjectError("target", "code2"));
            MethodParameter param = new MethodParameter(
                    SpringMvcExceptionStrategyTest.class.getDeclaredMethod("dummyMethod", Object.class), 0
            );
            var exception = new MethodArgumentNotValidException(param, bindingResult);

            // when
            assertThat(strategy.supports(exception)).isTrue();
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(entry.getErrorCode()).isEqualTo("INVALID_ARGUMENT");
            assertThat(entry.getReason()).isEqualTo("Validation failed");

            ErrorInfoDetail header = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(header.getClientMetadata()).containsEntry("violationCount", "2");
        }

        @Test
        @DisplayName("AsyncRequestTimeoutException -> 503 UNAVAILABLE + ASYNC_TIMEOUT granular")
        void asyncRequestTimeout_handled() {
            // given
            var exception = new AsyncRequestTimeoutException();

            // when
            assertThat(strategy.supports(exception)).isTrue();
            ErrorEntry entry = strategy.handle(exception, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(entry.getErrorCode()).isEqualTo("UNAVAILABLE");

            assertThat(entry.getDetails()).hasSize(2);
            ErrorInfoDetail granular = (ErrorInfoDetail) entry.getDetails().get(1);
            assertThat(granular.getReason()).isEqualTo("ASYNC_TIMEOUT");
        }
    }

    @SuppressWarnings("unused")
    private void dummyMethod(Object arg) {
    }
}
