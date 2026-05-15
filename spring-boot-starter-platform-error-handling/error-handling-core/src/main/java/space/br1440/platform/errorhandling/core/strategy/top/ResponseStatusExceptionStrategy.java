package space.br1440.platform.errorhandling.core.strategy.top;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.HttpStatusResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.utils.CanonicalErrors;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResponseStatusExceptionStrategy extends AbstractTopExceptionStrategy {

    private static final int ROUTING_MAX_DEPTH = 10;

    private final ResponseStatusResolverBean resolverBean;

    public ResponseStatusExceptionStrategy(@NonNull ResponseStatusResolverBean resolverBean,
                                           @NonNull CauseChainResolver causeChainResolver) {
        super(TopExceptionStrategyOrder.STATUS_FALLBACK, causeChainResolver);
        this.resolverBean = resolverBean;
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return resolverBean.resolve(exception, ROUTING_MAX_DEPTH).isPresent();
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        HttpStatusResolution resolved = resolverBean.resolve(exception, ROUTING_MAX_DEPTH)
                .orElseThrow(() -> new IllegalStateException(
                        "Вызван ResponseStatusExceptionStrategy.handle(), но HTTP-статус не удалось резолвнуть для "
                                + exception.getClass().getName()));

        HttpStatus httpStatus = resolved.httpStatus();
        String reasonPhrase = resolved.reasonPhrase();

        String clientMessage = httpStatus.is5xxServerError()
                ? httpStatus.getReasonPhrase()
                : reasonPhrase;

        Map<String, String> clientMeta = new LinkedHashMap<>();
        clientMeta.put("exceptionType", exception.getClass().getName());
        clientMeta.put("httpStatus", Integer.toString(httpStatus.value()));

        Map<String, String> diagnosticMeta = new LinkedHashMap<>();
        if (StringUtils.hasText(exception.getMessage())) {
            diagnosticMeta.put("exceptionMessage", exception.getMessage());
        }

        return createEntry(httpStatus, reasonPhrase, clientMessage, clientMeta, diagnosticMeta, context);
    }

    @NonNull
    private static ErrorEntry createEntry(HttpStatus httpStatus,
                                          @NonNull String reason,
                                          @NonNull String message,
                                          @NonNull Map<String, String> clientMeta,
                                          @NonNull Map<String, String> diagnosticMeta,
                                          @NonNull ErrorHandlingContext context) {
        String errorCode = CanonicalErrors.resolveErrorCode(httpStatus);

        ErrorInfoDetail.ErrorInfoDetailBuilder infoBuilder = ErrorInfoDetail.builder()
                .reason(reason)
                .domain("http")
                .clientMetadata(clientMeta);

        if (!diagnosticMeta.isEmpty()) {
            infoBuilder.diagnosticMetadata(diagnosticMeta);
        }

        ErrorInfoDetail info = infoBuilder.build();
        return ErrorEntry.builder()
                .httpStatus(httpStatus)
                .errorCode(errorCode)
                .message(message)
                .reason(reason)
                .requestURI(context.getRequestPath())
                .details(List.of(info))
                .build();
    }
}
