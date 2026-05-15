package space.br1440.platform.errorhandling.core.strategy.top;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.NotAcceptableStatusException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.HttpStatusToErrorCodeMapper;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WebFluxFrameworkExceptionStrategy extends AbstractTopExceptionStrategy {

    private static final Set<Class<?>> ROUTING_TYPES = Set.of(
            MethodNotAllowedException.class,
            NotAcceptableStatusException.class,
            UnsupportedMediaTypeStatusException.class
    );

    public WebFluxFrameworkExceptionStrategy(@NonNull CauseChainResolver causeChainResolver) {
        super(TopExceptionStrategyOrder.FRAMEWORK, causeChainResolver);
    }

    @Override
    @NonNull
    public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry errorEntry) {
        if (isExplicitRoutingException(exception)) {
            return LogPolicy.DEBUG;
        }

        if (is404(exception, errorEntry)) {
            return LogPolicy.WARN;
        }

        HttpStatus status = errorEntry.getHttpStatus();
        if (status.is4xxClientError()) {
            return LogPolicy.WARN;
        }

        return LogPolicy.ERROR;
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return (exception instanceof ResponseStatusException);
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        ResponseStatusException rse = (ResponseStatusException) exception;
        HttpStatus httpStatus = HttpStatus.resolve(rse.getStatusCode().value());
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        String topErrorCode = HttpStatusToErrorCodeMapper.map(httpStatus);
        String message = resolveMessage(rse, httpStatus);

        Map<String, String> diagnosticMetadata = DiagnosticMetadataBuilder
                .forCause(exception, context.getStackHintExtractor())
                .putIfPresent("reason", rse.getReason())
                .build();

        ErrorInfoDetail header = ErrorInfoDetail.builder()
                .reason(topErrorCode)
                .domain("webflux")
                .diagnosticMetadata(diagnosticMetadata)
                .build();

        List<ErrorDetail> details = new ArrayList<>(2);
        details.add(header);

        String granular = mapToGranularReason(exception, httpStatus);
        if (granular != null && !granular.equals(topErrorCode)) {
            details.add(ErrorInfoDetail.builder()
                    .reason(granular)
                    .domain("webflux")
                    .build());
        }

        return ErrorEntry.builder()
                .httpStatus(httpStatus)
                .errorCode(topErrorCode)
                .message(message)
                .requestURI(context.getRequestPath())
                .details(Collections.unmodifiableList(details))
                .build();
    }

    private static boolean isExplicitRoutingException(@NonNull Exception exception) {
        for (Class<?> routingType : ROUTING_TYPES) {
            if (routingType.isInstance(exception)) {
                return true;
            }
        }

        return false;
    }

    private static boolean is404(@NonNull Exception exception, @NonNull ErrorEntry errorEntry) {
        if (exception instanceof ResponseStatusException rse && rse.getStatusCode().value() == 404) {
            return true;
        }

        return (errorEntry.getHttpStatus() == HttpStatus.NOT_FOUND);
    }

    private static String mapToGranularReason(@NonNull Exception exception, @NonNull HttpStatus status) {
        return switch (exception) {
            case MethodNotAllowedException ignored -> "METHOD_NOT_ALLOWED";
            case NotAcceptableStatusException ignored -> "NOT_ACCEPTABLE";
            case UnsupportedMediaTypeStatusException ignored -> "UNSUPPORTED_MEDIA_TYPE";
            default -> switch (status) {
                case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
                case NOT_ACCEPTABLE -> "NOT_ACCEPTABLE";
                case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
                case PAYLOAD_TOO_LARGE -> "PAYLOAD_TOO_LARGE";
                case TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS";
                default -> null;
            };
        };
    }

    @NonNull
    private static String resolveMessage(@NonNull ResponseStatusException statusException,
                                         @NonNull HttpStatus httpStatus) {
        if (StringUtils.hasText(statusException.getReason())) {
            return statusException.getReason();
        }

        return httpStatus.getReasonPhrase();
    }
}
