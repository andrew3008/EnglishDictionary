package space.br1440.platform.errorhandling.core.strategy.top;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.lang.NonNull;
import org.springframework.web.ErrorResponse;
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
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.strategy.top.mapping.ExceptionMapping;
import space.br1440.platform.errorhandling.core.strategy.top.mapping.SpringMvcExceptionMappings;
import space.br1440.platform.errorhandling.core.util.HttpStatusToErrorCodeMapper;
import space.br1440.platform.errorhandling.core.util.sanitize.ErrorMessageSanitizer;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SpringMvcExceptionStrategy extends AbstractTopExceptionStrategy {

    /**
     * Routing-исключения логируются на уровне {@link LogPolicy#DEBUG} —
     * это ожидаемый трафик, не являющийся признаком ошибки на стороне сервера.
     */
    private static final Set<Class<?>> ROUTING_TYPES = Set.of(
            NoHandlerFoundException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class
    );

    private static final Set<Class<?>> SUPPORTED_TYPES = Set.of(
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            ServletRequestBindingException.class,
            MissingPathVariableException.class,
            HttpMessageNotReadableException.class,
            HttpMessageNotWritableException.class,
            MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class,
            ConversionNotSupportedException.class,
            TypeMismatchException.class,
            NoHandlerFoundException.class,
            NoResourceFoundException.class,
            MaxUploadSizeExceededException.class,
            AsyncRequestTimeoutException.class,
            ErrorResponse.class
    );

    /**
     * Верхний предел длины публичного {@code message}: защита от log-DoS и unbounded response body.
     * Подавляющее большинство сообщений укладываются в 200–300 символов;
     * всё что длиннее — обрезается с маркером {@code [TRUNCATED]}.
     */
    private static final int MAX_MESSAGE_LENGTH = 300;

    private static final ClassValue<Boolean> SUPPORTS_CACHE = new ClassValue<>() {

        @Override
        protected Boolean computeValue(@NotNull Class<?> type) {
            for (Class<?> supported : SUPPORTED_TYPES) {
                if (supported.isAssignableFrom(type)) {
                    return Boolean.TRUE;
                }
            }

            return Boolean.FALSE;
        }
    };

    public SpringMvcExceptionStrategy(@NonNull CauseChainResolver causeChainResolver) {
        super(TopExceptionStrategyOrder.FRAMEWORK, causeChainResolver);
    }

    @Override
    @NonNull
    public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        if (ROUTING_TYPES.stream().anyMatch(t -> t.isInstance(exception))) {
            return LogPolicy.DEBUG;
        }

        HttpStatus httpStatus = entry.getHttpStatus();
        if (httpStatus.is5xxServerError()) {
            return LogPolicy.ERROR;
        } else if (httpStatus.is4xxClientError()) {
            return LogPolicy.WARN;
        }

        return LogPolicy.DEBUG;
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return SUPPORTS_CACHE.get(exception.getClass());
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception,
                                  @NonNull ErrorHandlingContext context) {
        ExceptionMapping mapping = SpringMvcExceptionMappings.tryMap(exception)
                .orElseGet(() -> resolveErrorResponseMapping(exception));

        return buildEntry(mapping, context);
    }

    private ErrorEntry buildEntry(@NonNull ExceptionMapping mapping,
                                  @NonNull ErrorHandlingContext context) {
        String topErrorCode = HttpStatusToErrorCodeMapper.map(mapping.httpStatus());

        ErrorInfoDetail header = ErrorInfoDetail.builder()
                .reason(topErrorCode)
                .domain("http")
                .clientMetadata(mapping.clientMetadata())
                .build();

        List<ErrorDetail> details = new ArrayList<>(2);
        details.add(header);

        String granular = mapping.granularReason();
        if (granular != null && !granular.equals(topErrorCode)) {
            details.add(ErrorInfoDetail.builder()
                    .reason(granular)
                    .domain("http")
                    .build());
        }

        String capped = capMessage(mapping.message());

        return ErrorEntry.builder()
                .httpStatus(mapping.httpStatus())
                .errorCode(topErrorCode)
                .message(capped)
                .reason(mapping.reason())
                .requestURI(context.getRequestPath())
                .details(Collections.unmodifiableList(details))
                .build();
    }

    @NonNull
    private ExceptionMapping resolveErrorResponseMapping(@NonNull Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatus status = Optional
                    .ofNullable(HttpStatus.resolve(errorResponse.getStatusCode().value()))
                    .orElse(HttpStatus.INTERNAL_SERVER_ERROR);

            String reason = status.getReasonPhrase();
            return ExceptionMapping.of(
                    status,
                    reason,
                    reason,
                    null,
                    Map.of("httpStatus", Integer.toString(status.value()))
            );
        }

        String type = ErrorMessageSanitizer.sanitizeToken(exception.getClass().getName());
        return ExceptionMapping.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error",
                "Unexpected error of type '" + type + "'",
                null,
                Map.of("exceptionType", type)
        );
    }

    @NonNull
    private static String capMessage(@NonNull String message) {
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }

        return message.substring(0, MAX_MESSAGE_LENGTH) + " [TRUNCATED]";
    }
}
