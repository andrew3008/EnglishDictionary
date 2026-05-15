package space.br1440.platform.errorhandling.core.strategy.top.mapping;

import lombok.experimental.UtilityClass;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
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
import space.br1440.platform.errorhandling.core.util.sanitize.ErrorMessageSanitizer;

import java.util.Map;
import java.util.Optional;

@UtilityClass
public final class SpringMvcExceptionMappings {

    public static Optional<ExceptionMapping> tryMap(Exception exception) {
        return Optional.ofNullable(switch (exception) {
            case MissingServletRequestParameterException e -> mapMissingServletRequestParameter(e);
            case MissingServletRequestPartException e -> mapMissingServletRequestPart(e);
            case MethodArgumentTypeMismatchException e -> mapMethodArgumentTypeMismatch(e);
            case ConversionNotSupportedException e -> mapConversionNotSupported(e);
            case TypeMismatchException e -> mapTypeMismatch(e);
            case MissingPathVariableException e -> mapMissingPathVariable(e);
            case NoHandlerFoundException e -> mapNoHandlerFound(e);
            case NoResourceFoundException e -> mapNoResourceFound(e);
            case HttpRequestMethodNotSupportedException e -> mapHttpRequestMethodNotSupported(e);
            case HttpMediaTypeNotAcceptableException e -> mapHttpMediaTypeNotAcceptable(e);
            case HttpMediaTypeNotSupportedException e -> mapHttpMediaTypeNotSupported(e);
            case MaxUploadSizeExceededException e -> mapMaxUploadSizeExceeded(e);
            case HttpMessageNotReadableException ignored -> mapHttpMessageNotReadable();
            case MethodArgumentNotValidException e -> mapMethodArgumentNotValid(e);
            case AsyncRequestTimeoutException ignored -> mapAsyncRequestTimeout();
            case ServletRequestBindingException ignored -> mapServletRequestBinding();
            case HttpMessageNotWritableException ignored -> mapHttpMessageNotWritable();
            default -> null;
        });
    }

    private static ExceptionMapping mapMissingServletRequestParameter(MissingServletRequestParameterException e) {
        String paramName = ErrorMessageSanitizer.sanitizeToken(e.getParameterName());
        String paramType = ErrorMessageSanitizer.sanitizeToken(e.getParameterType());
        return ExceptionMapping.of(
                HttpStatus.BAD_REQUEST,
                "Required parameter is missing",
                "Required parameter '" + paramName + "' of type '" + paramType + "' is missing",
                "MISSING_REQUEST_PARAMETER",
                Map.of("parameterName", paramName, "parameterType", paramType)
        );
    }

    private static ExceptionMapping mapMissingServletRequestPart(MissingServletRequestPartException e) {
        String partName = ErrorMessageSanitizer.sanitizeToken(e.getRequestPartName());
        return ExceptionMapping.of(
                HttpStatus.BAD_REQUEST,
                "Required request part is missing",
                "Required request part '" + partName + "' is missing",
                "MISSING_REQUEST_PART",
                Map.of("partName", partName)
        );
    }

    private static ExceptionMapping mapMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        String paramName = ErrorMessageSanitizer.sanitizeToken(e.getName());
        String type = ErrorMessageSanitizer.sanitizeToken(MvcMappingHelpers.resolveTypeName(e.getRequiredType()));
        return ExceptionMapping.of(
                HttpStatus.BAD_REQUEST,
                "Invalid parameter type",
                "Parameter '" + paramName + "' could not be converted to type '" + type + "'",
                "TYPE_MISMATCH",
                Map.of("parameterName", paramName, "expectedType", type)
        );
    }

    private static ExceptionMapping mapConversionNotSupported(ConversionNotSupportedException e) {
        String required = ErrorMessageSanitizer.sanitizeToken(MvcMappingHelpers.resolveTypeName(e.getRequiredType()));
        return ExceptionMapping.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Type conversion not supported",
                "Type conversion not supported for required type '" + required + "'",
                null,
                Map.of("requiredType", required)
        );
    }

    private static ExceptionMapping mapTypeMismatch(TypeMismatchException e) {
        String required = ErrorMessageSanitizer.sanitizeToken(MvcMappingHelpers.resolveTypeName(e.getRequiredType()));
        return ExceptionMapping.of(
                HttpStatus.BAD_REQUEST,
                "Type mismatch",
                "Value could not be converted to required type '" + required + "'",
                "TYPE_MISMATCH",
                Map.of("requiredType", required)
        );
    }

    private static ExceptionMapping mapMissingPathVariable(MissingPathVariableException e) {
        String varName = ErrorMessageSanitizer.sanitizeToken(e.getVariableName());
        return ExceptionMapping.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Missing path variable",
                "Missing path variable '" + varName + "'",
                null,
                Map.of("variableName", varName)
        );
    }

    private static ExceptionMapping mapNoHandlerFound(NoHandlerFoundException e) {
        String method = ErrorMessageSanitizer.sanitizeHttpMethod(e.getHttpMethod());
        String url = ErrorMessageSanitizer.sanitizeUrl(e.getRequestURL());
        return ExceptionMapping.of(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                "No handler found for " + method + " " + url,
                null,
                Map.of("method", method, "requestUrl", url)
        );
    }

    private static ExceptionMapping mapNoResourceFound(NoResourceFoundException e) {
        String resourcePath = ErrorMessageSanitizer.sanitizeUrl(e.getResourcePath());
        return ExceptionMapping.of(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                "Resource not found at path " + resourcePath,
                null,
                Map.of("resourcePath", resourcePath)
        );
    }

    private static ExceptionMapping mapHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        String method = ErrorMessageSanitizer.sanitizeHttpMethod(e.getMethod());
        String supported = MvcMappingHelpers.sanitizeMethodList(e.getSupportedMethods());
        return ExceptionMapping.of(
                HttpStatus.METHOD_NOT_ALLOWED,
                "HTTP method not supported",
                "HTTP method '" + method + "' is not supported; supported: " + supported,
                "METHOD_NOT_ALLOWED",
                Map.of("method", method, "supportedMethods", supported)
        );
    }

    private static ExceptionMapping mapHttpMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        String supported = ErrorMessageSanitizer.sanitizeToken(
                MvcMappingHelpers.mediaTypesToString(e.getSupportedMediaTypes()));
        return ExceptionMapping.of(
                HttpStatus.NOT_ACCEPTABLE,
                "Media type not acceptable",
                "Requested media type is not acceptable; supported: " + supported,
                "NOT_ACCEPTABLE",
                Map.of("supportedTypes", supported)
        );
    }

    private static ExceptionMapping mapHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        String contentType = ErrorMessageSanitizer.sanitizeToken(String.valueOf(e.getContentType()));
        String supported = ErrorMessageSanitizer.sanitizeToken(
                MvcMappingHelpers.mediaTypesToString(e.getSupportedMediaTypes()));
        return ExceptionMapping.of(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Media type not supported",
                "Content-Type '" + contentType + "' is not supported; supported: " + supported,
                "UNSUPPORTED_MEDIA_TYPE",
                Map.of("contentType", contentType, "supportedTypes", supported)
        );
    }

    private static ExceptionMapping mapMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        long maxSize = e.getMaxUploadSize();
        return ExceptionMapping.of(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Payload too large",
                "Upload exceeds maximum size of " + maxSize + " bytes",
                "PAYLOAD_TOO_LARGE",
                Map.of("maxUploadSize", Long.toString(maxSize))
        );
    }

    private static ExceptionMapping mapHttpMessageNotReadable() {
        return ExceptionMapping.of(
                HttpStatus.BAD_REQUEST,
                "Invalid request body",
                "Invalid request body",
                "MALFORMED_REQUEST_BODY",
                Map.of()
        );
    }

    private static ExceptionMapping mapMethodArgumentNotValid(MethodArgumentNotValidException e) {
        int violations = e.getBindingResult().getErrorCount();
        return ExceptionMapping.of(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                "Validation failed: " + violations + " field violation(s)",
                "VALIDATION_ERROR",
                Map.of("violationCount", Integer.toString(violations))
        );
    }

    private static ExceptionMapping mapAsyncRequestTimeout() {
        return ExceptionMapping.of(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Async request timed out",
                "Async request timed out",
                "ASYNC_TIMEOUT",
                Map.of()
        );
    }

    private static ExceptionMapping mapServletRequestBinding() {
        return ExceptionMapping.of(
                HttpStatus.BAD_REQUEST,
                "Request binding error",
                "Request binding error",
                "REQUEST_BINDING_ERROR",
                Map.of()
        );
    }

    private static ExceptionMapping mapHttpMessageNotWritable() {
        return ExceptionMapping.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Response serialization error",
                "Response serialization error",
                null,
                Map.of()
        );
    }
}
