package space.br1440.platform.errorhandling.core.strategy.top.mapping;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.core.MethodParameter;
import org.springframework.validation.MapBindingResult;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import org.springframework.http.HttpInputMessage;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpringMvcExceptionMappings")
class SpringMvcExceptionMappingsTest {

    @Test
    @DisplayName("tryMap: неизвестный тип → empty")
    void tryMap_unknownType_returnsEmpty() {
        // given
        var ex = new IllegalStateException("x");

        // when
        var mapped = SpringMvcExceptionMappings.tryMap(ex);

        // then
        assertThat(mapped).isEmpty();
    }

    @Nested
    @DisplayName("маппинг по типу исключения")
    class PerType {

        @Test
        void missingServletRequestParameter() {
            // given
            var ex = new MissingServletRequestParameterException("id", "String");

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(m.reason()).isEqualTo("Required parameter is missing");
            assertThat(m.message()).contains("id").contains("String");
            assertThat(m.granularReason()).isEqualTo("MISSING_REQUEST_PARAMETER");
            assertThat(m.clientMetadata()).containsEntry("parameterName", "id").containsEntry("parameterType", "String");
        }

        @Test
        void missingServletRequestParameter_nullNames_sanitized() {
            // given
            var ex = new MissingServletRequestParameterException("", "String");

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(m.clientMetadata()).containsKey("parameterName");
        }

        @Test
        void missingServletRequestPart() {
            // given
            var ex = new MissingServletRequestPartException("file");

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(m.granularReason()).isEqualTo("MISSING_REQUEST_PART");
            assertThat(m.message()).contains("file");
        }

        @Test
        void methodArgumentTypeMismatch() throws Exception {
            // given
            MethodParameter mp = new MethodParameter(String.class.getDeclaredMethod("length"), -1);
            var ex = new MethodArgumentTypeMismatchException("x", Integer.class, "n", mp, null);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(m.granularReason()).isEqualTo("TYPE_MISMATCH");
            assertThat(m.clientMetadata()).containsEntry("parameterName", "n");
        }

        @Test
        void methodArgumentTypeMismatch_nullRequiredType() throws Exception {
            // given
            MethodParameter mp = new MethodParameter(
                    String.class.getDeclaredMethod("length"), -1);
            var ex = new MethodArgumentTypeMismatchException("x", null, "n", mp, null);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.clientMetadata()).containsEntry("expectedType", "unknown");
        }

        @Test
        void conversionNotSupported() {
            // given
            var ex = new ConversionNotSupportedException("x", Double.class, null);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(m.message()).contains("Double");
        }

        @Test
        void typeMismatch() {
            // given
            var ex = new TypeMismatchException("x", Long.class);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(m.granularReason()).isEqualTo("TYPE_MISMATCH");
        }

        @Test
        void typeMismatch_nullRequiredType() {
            // given
            var ex = new TypeMismatchException("x", null);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.clientMetadata()).containsEntry("requiredType", "unknown");
        }

        @Test
        void missingPathVariable() throws Exception {
            // given
            MethodParameter mp = new MethodParameter(String.class.getDeclaredMethod("length"), -1);
            var ex = new MissingPathVariableException("orderId", mp);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(m.message()).contains("orderId");
        }

        @Test
        void noHandlerFound() {
            // given
            var ex = new NoHandlerFoundException("GET", "/api/missing", HttpHeaders.EMPTY);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(m.message()).contains("GET");
        }

        @Test
        void noResourceFound() {
            // given
            var ex = new NoResourceFoundException(HttpMethod.GET, "/static/foo.css");

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(m.clientMetadata()).containsKey("resourcePath");
        }

        @Test
        void httpRequestMethodNotSupported() {
            // given
            var ex = new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "POST"));

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(m.granularReason()).isEqualTo("METHOD_NOT_ALLOWED");
            assertThat(m.message()).contains("PATCH").contains("GET").contains("POST");
        }

        @Test
        void httpRequestMethodNotSupported_nullSupportedMethods() {
            // given
            var ex = new HttpRequestMethodNotSupportedException("PUT", null);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.message()).contains("none");
        }

        @Test
        void httpMediaTypeNotAcceptable() {
            // given
            var ex = new HttpMediaTypeNotAcceptableException(Collections.emptyList());

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
            assertThat(m.granularReason()).isEqualTo("NOT_ACCEPTABLE");
        }

        @Test
        void httpMediaTypeNotSupported() {
            // given
            var ex = new HttpMediaTypeNotSupportedException("text/plain", Collections.emptyList());

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(m.granularReason()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        }

        @Test
        void maxUploadSizeExceeded() {
            // given
            var ex = new MaxUploadSizeExceededException(1024L);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(m.message()).contains("1024");
            assertThat(m.granularReason()).isEqualTo("PAYLOAD_TOO_LARGE");
        }

        @Test
        void httpMessageNotReadable() {
            // given
            HttpInputMessage emptyInput = new HttpInputMessage() {
                @Override
                public @NotNull InputStream getBody() {
                    return InputStream.nullInputStream();
                }

                @Override
                public @NotNull HttpHeaders getHeaders() {
                    return HttpHeaders.EMPTY;
                }
            };
            var ex = new HttpMessageNotReadableException("bad", emptyInput);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(m.granularReason()).isEqualTo("MALFORMED_REQUEST_BODY");
        }

        @Test
        void methodArgumentNotValid() throws Exception {
            // given
            MapBindingResult br = new MapBindingResult(Collections.emptyMap(), "obj");
            br.addError(new org.springframework.validation.ObjectError("obj", "e"));
            MethodParameter mp = new MethodParameter(String.class.getDeclaredMethod("length"), -1);
            var ex = new MethodArgumentNotValidException(mp, br);

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(m.granularReason()).isEqualTo("VALIDATION_ERROR");
            assertThat(m.message()).contains("1");
        }

        @Test
        void asyncRequestTimeout() {
            // given
            var ex = new AsyncRequestTimeoutException();

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(m.granularReason()).isEqualTo("ASYNC_TIMEOUT");
        }

        @Test
        void servletRequestBinding() {
            // given
            var ex = new ServletRequestBindingException("bind");

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(m.granularReason()).isEqualTo("REQUEST_BINDING_ERROR");
        }

        @Test
        void httpMessageNotWritable() {
            // given
            var ex = new HttpMessageNotWritableException("w");

            // when
            ExceptionMapping m = SpringMvcExceptionMappings.tryMap(ex).orElseThrow();

            // then
            assertThat(m.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(m.granularReason()).isNull();
        }
    }
}
