package space.br1440.platform.errorhandling.core.strategy.top;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.BadRequestDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;

import java.util.*;

/**
 * Стратегия обработки ошибок Bean Validation.
 *
 * <h2>Поддерживаемые исключения</h2>
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} — {@code @Valid} / {@code @Validated}
 *       на параметрах {@code @RequestBody} и {@code @ModelAttribute}</li>
 *   <li>{@link HandlerMethodValidationException} — {@code @Validated} на уровне класса
 *       контроллера (валидация скалярных параметров: {@code @PathVariable}, {@code @RequestParam})</li>
 *   <li>{@code ConstraintViolationException} — Jakarta Bean Validation напрямую (сервисный слой, programmatic validation).
 *       Загружается рефлективно для совместимости с classpath без Jakarta Validation API.</li>
 * </ul>
 */
public class ValidationExceptionStrategy extends AbstractTopExceptionStrategy {

    private static final ClassLoader LOADER = ValidationExceptionStrategy.class.getClassLoader();
    private static final Class<?> CONSTRAINT_VIOLATION_CLASS = ReflectionUtils.loadOptionalClass(
            "jakarta.validation.ConstraintViolationException", LOADER
    );

    public ValidationExceptionStrategy(@NonNull CauseChainResolver causeChainResolver) {
        super(TopExceptionStrategyOrder.VALIDATION, causeChainResolver);
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return exception instanceof MethodArgumentNotValidException
                || exception instanceof HandlerMethodValidationException
                || ClassUtils.isInstanceOf(CONSTRAINT_VIOLATION_CLASS, exception);
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        if (exception instanceof MethodArgumentNotValidException e) {
            return createMethodArgumentNotValidEntry(e, context);
        } else if (exception instanceof HandlerMethodValidationException e) {
            return createHandlerMethodValidationEntry(e, context);
        } else if (ClassUtils.isInstanceOf(CONSTRAINT_VIOLATION_CLASS, exception)) {
            return createConstraintViolationEntry(exception, context);
        }

        throw new AssertionError(
                "ValidationExceptionStrategy.doHandle() called for unsupported exception: "
                        + exception.getClass().getName());
    }

    @NonNull
    private ErrorEntry createMethodArgumentNotValidEntry(@NonNull MethodArgumentNotValidException exception,
                                                         @NonNull ErrorHandlingContext context) {
        BadRequestDetail.BadRequestDetailBuilder badRequestBuilder = BadRequestDetail.builder();
        exception.getBindingResult().getAllErrors().forEach(error -> {
            if (error instanceof FieldError fieldError) {
                badRequestBuilder.addFieldViolation(fieldError.getField(), "INVALID", fieldError.getDefaultMessage());
            } else {
                badRequestBuilder.addFieldViolation("", "INVALID", error.getDefaultMessage());
            }
        });
        BadRequestDetail badRequest = badRequestBuilder.build();

        ErrorInfoDetail errorInfo = ErrorInfoDetail.builder()
                .reason("VALIDATION_FAILED")
                .domain("validation")
                .clientMetadata(Map.of("violationCount", String.valueOf(badRequest.getFieldViolations().size())))
                .build();

        List<ErrorDetail> details = new ArrayList<>();
        details.add(badRequest);
        details.add(errorInfo);

        return createEntry(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Validation failed", "Invalid Argument", details, context);
    }

    @NonNull
    private ErrorEntry createHandlerMethodValidationEntry(@NonNull HandlerMethodValidationException exception,
                                                          @NonNull ErrorHandlingContext context) {
        BadRequestDetail.BadRequestDetailBuilder badRequestBuilder = BadRequestDetail.builder();
        exception.getParameterValidationResults().forEach(result -> {
            String paramName = Optional.ofNullable(result.getMethodParameter().getParameterName()).orElse("unknown");
            result.getResolvableErrors().forEach(error ->
                    badRequestBuilder.addFieldViolation(paramName, "INVALID", error.getDefaultMessage()));
        });
        BadRequestDetail badRequest = badRequestBuilder.build();

        ErrorInfoDetail errorInfo = ErrorInfoDetail.builder()
                .reason("VALIDATION_FAILED")
                .domain("validation")
                .clientMetadata(Map.of("violationCount", String.valueOf(badRequest.getFieldViolations().size())))
                .build();

        List<ErrorDetail> details = new ArrayList<>();
        details.add(badRequest);
        details.add(errorInfo);

        return createEntry(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Validation failed", "Invalid Argument", details, context);
    }

    @NonNull
    private ErrorEntry createConstraintViolationEntry(@NonNull Exception exception,
                                                      @NonNull ErrorHandlingContext context) {
        BadRequestDetail.BadRequestDetailBuilder badRequestBuilder = BadRequestDetail.builder();

        Set<?> violations = extractViolations(exception);
        violations.forEach(violation -> addFieldViolation(badRequestBuilder, violation));

        BadRequestDetail badRequest = badRequestBuilder.build();
        if (badRequest.getFieldViolations().isEmpty()) {
            badRequest = BadRequestDetail.builder()
                    .addFieldViolation("", "INVALID", "Validation failed")
                    .build();
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("violationCount", String.valueOf(violations.size()));
        meta.put("validationScope", "SERVICE_LAYER");

        ErrorInfoDetail.ErrorInfoDetailBuilder errorInfoBuilder = ErrorInfoDetail.builder()
                .reason("VALIDATION_FAILED")
                .domain("validation")
                .clientMetadata(meta);

        if (StringUtils.hasText(exception.getMessage())) {
            errorInfoBuilder.diagnosticMetadata(Collections.singletonMap("exceptionMessage", exception.getMessage()));
        }

        ErrorInfoDetail errorInfo = errorInfoBuilder.build();

        List<ErrorDetail> details = new ArrayList<>();
        details.add(badRequest);
        details.add(errorInfo);

        return createEntry(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Validation failed", "Invalid Argument", details, context);
    }

    @NonNull
    private static ErrorEntry createEntry(HttpStatus httpStatus,
                                          @NonNull String errorCode,
                                          @NonNull String message,
                                          @NonNull String reason,
                                          @NonNull List<ErrorDetail> details,
                                          @NonNull ErrorHandlingContext context) {
        return ErrorEntry.builder()
                .httpStatus(httpStatus)
                .errorCode(errorCode)
                .message(message)
                .reason(reason)
                .requestURI(context.getRequestPath())
                .details(Collections.unmodifiableList(details))
                .build();
    }

    @NonNull
    private Set<?> extractViolations(@NonNull Exception exception) {
        Set<?>[] holder = new Set<?>[1];
        ReflectionUtils.invokeMethod(exception, "getConstraintViolations",value -> holder[0] = (Set<?>) value);
        return (holder[0] != null) ? holder[0] : Collections.emptySet();
    }

    private static void addFieldViolation(@NonNull BadRequestDetail.BadRequestDetailBuilder builder,
                                          @NonNull Object violation) {
        String[] fieldHolder = {""};
        String[] messageHolder = {"Validation failed"};

        ReflectionUtils.invokeMethod(violation, "getPropertyPath",value -> fieldHolder[0] = String.valueOf(value));
        ReflectionUtils.invokeMethod(violation, "getMessage",value -> {
            if (value instanceof String s) {
                messageHolder[0] = s;
            }
        });

        builder.addFieldViolation(fieldHolder[0], "INVALID", messageHolder[0]);
    }
}
