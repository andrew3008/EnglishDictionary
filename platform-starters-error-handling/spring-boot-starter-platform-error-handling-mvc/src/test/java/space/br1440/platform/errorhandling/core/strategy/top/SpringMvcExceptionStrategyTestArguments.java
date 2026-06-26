package space.br1440.platform.errorhandling.core.strategy.top;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

final class SpringMvcExceptionStrategyTestArguments {

    private SpringMvcExceptionStrategyTestArguments() {
    }

    static HttpInputMessage emptyHttpInputMessage() {
        return new HttpInputMessage() {
            @Override
            public @NotNull InputStream getBody() {
                return InputStream.nullInputStream();
            }

            @Override
            public @NotNull HttpHeaders getHeaders() {
                return HttpHeaders.EMPTY;
            }
        };
    }

    static Stream<Arguments> supportedExceptionProvider() {
        return Stream.of(
                Arguments.of("MissingServletRequestParameterException",
                        new MissingServletRequestParameterException("p", "String")),

                Arguments.of("MissingServletRequestPartException",
                        new MissingServletRequestPartException("file")),

                Arguments.of("HttpMessageNotReadableException",
                        new HttpMessageNotReadableException("bad", emptyHttpInputMessage())),

                Arguments.of("HttpMessageNotWritableException",
                        new HttpMessageNotWritableException("bad")),

                Arguments.of("TypeMismatchException",
                        new TypeMismatchException("val", Integer.class)),

                Arguments.of("ConversionNotSupportedException",
                        new ConversionNotSupportedException("val", Integer.class, null)),

                Arguments.of("NoHandlerFoundException",
                        new NoHandlerFoundException("GET", "/test", HttpHeaders.EMPTY)),

                Arguments.of("NoResourceFoundException",
                        new NoResourceFoundException(HttpMethod.GET, "/test")),

                Arguments.of("HttpRequestMethodNotSupportedException",
                        new HttpRequestMethodNotSupportedException("PATCH")),

                Arguments.of("HttpMediaTypeNotAcceptableException",
                        new HttpMediaTypeNotAcceptableException("not acceptable")),

                Arguments.of("HttpMediaTypeNotSupportedException",
                        new HttpMediaTypeNotSupportedException("unsupported")),

                Arguments.of("MaxUploadSizeExceededException",
                        new MaxUploadSizeExceededException(1024L)),

                Arguments.of("ServletRequestBindingException",
                        new ServletRequestBindingException("binding error"))
        );
    }

    static Stream<Arguments> httpStatusMappingProvider() {
        return Stream.of(
                Arguments.of("MissingServletRequestParameterException", HttpStatus.BAD_REQUEST,
                        new MissingServletRequestParameterException("p", "String")),

                Arguments.of("MissingServletRequestPartException", HttpStatus.BAD_REQUEST,
                        new MissingServletRequestPartException("file")),

                Arguments.of("HttpMessageNotReadableException", HttpStatus.BAD_REQUEST,
                        new HttpMessageNotReadableException("bad", emptyHttpInputMessage())),

                Arguments.of("TypeMismatchException", HttpStatus.BAD_REQUEST,
                        new TypeMismatchException("val", Integer.class)),

                Arguments.of("ServletRequestBindingException", HttpStatus.BAD_REQUEST,
                        new ServletRequestBindingException("binding")),

                Arguments.of("NoHandlerFoundException", HttpStatus.NOT_FOUND,
                        new NoHandlerFoundException("GET", "/test", HttpHeaders.EMPTY)),

                Arguments.of("NoResourceFoundException", HttpStatus.NOT_FOUND,
                        new NoResourceFoundException(HttpMethod.GET, "/test")),

                Arguments.of("HttpRequestMethodNotSupportedException", HttpStatus.METHOD_NOT_ALLOWED,
                        new HttpRequestMethodNotSupportedException("PATCH")),

                Arguments.of("HttpMediaTypeNotAcceptableException", HttpStatus.NOT_ACCEPTABLE,
                        new HttpMediaTypeNotAcceptableException("not acceptable")),

                Arguments.of("HttpMediaTypeNotSupportedException", HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        new HttpMediaTypeNotSupportedException(MediaType.TEXT_XML, List.of(MediaType.APPLICATION_JSON))),

                Arguments.of("MaxUploadSizeExceededException", HttpStatus.PAYLOAD_TOO_LARGE,
                        new MaxUploadSizeExceededException(1024L)),

                Arguments.of("ConversionNotSupportedException", HttpStatus.INTERNAL_SERVER_ERROR,
                        new ConversionNotSupportedException("val", Integer.class, null)),

                Arguments.of("HttpMessageNotWritableException", HttpStatus.INTERNAL_SERVER_ERROR,
                        new HttpMessageNotWritableException("bad"))
        );
    }

    static Stream<Arguments> errorCodeMappingProvider() {
        return Stream.of(
                Arguments.of("MissingServletRequestParameterException", "INVALID_ARGUMENT",
                        new MissingServletRequestParameterException("p", "String")),

                Arguments.of("HttpMessageNotReadableException", "INVALID_ARGUMENT",
                        new HttpMessageNotReadableException("bad", emptyHttpInputMessage())),

                Arguments.of("NoHandlerFoundException", "NOT_FOUND",
                        new NoHandlerFoundException("GET", "/test", HttpHeaders.EMPTY)),

                Arguments.of("NoResourceFoundException", "NOT_FOUND",
                        new NoResourceFoundException(HttpMethod.GET, "/test")),

                Arguments.of("HttpRequestMethodNotSupportedException", "INVALID_ARGUMENT",
                        new HttpRequestMethodNotSupportedException("PATCH")),

                Arguments.of("HttpMediaTypeNotAcceptableException", "INVALID_ARGUMENT",
                        new HttpMediaTypeNotAcceptableException("not acceptable")),

                Arguments.of("HttpMediaTypeNotSupportedException", "INVALID_ARGUMENT",
                        new HttpMediaTypeNotSupportedException(MediaType.TEXT_XML, List.of(MediaType.APPLICATION_JSON))),

                Arguments.of("MaxUploadSizeExceededException", "RESOURCE_EXHAUSTED",
                        new MaxUploadSizeExceededException(1024L)),

                Arguments.of("ConversionNotSupportedException", "INTERNAL",
                        new ConversionNotSupportedException("val", Integer.class, null)),

                Arguments.of("HttpMessageNotWritableException", "INTERNAL",
                        new HttpMessageNotWritableException("bad"))
        );
    }
}
