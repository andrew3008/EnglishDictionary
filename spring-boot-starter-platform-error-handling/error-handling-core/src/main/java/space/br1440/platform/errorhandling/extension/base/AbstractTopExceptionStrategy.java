package space.br1440.platform.errorhandling.extension.base;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.core.util.ReasonCodeUtils;
import space.br1440.platform.errorhandling.core.util.RetryabilityResolver;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractTopExceptionStrategy implements TopExceptionHandlingStrategy {

    private final int order;

    protected final CauseChainResolver causeChainResolver;

    protected AbstractTopExceptionStrategy(int order, @NonNull CauseChainResolver causeChainResolver) {
        if (order <= 0) {
            throw new IllegalArgumentException("order must be positive, got: " + order);
        }

        this.order = order;
        this.causeChainResolver = causeChainResolver;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    @NonNull
    public final ErrorEntry handle(@NonNull Exception exception,
                                   @NonNull ErrorHandlingContext context) {
        return enrichFromCauseChain(exception, doHandle(exception, context));
    }

    @NonNull
    protected ErrorEntry enrichFromCauseChain(@NonNull Exception exception, @NonNull ErrorEntry base) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            return base;
        }

        ResolvedRootCause resolved = causeChainResolver.resolve(cause);
        return applyResolvedCause(base, resolved);
    }

    @NonNull
    protected abstract ErrorEntry doHandle(@NonNull Exception exception,
                                           @NonNull ErrorHandlingContext context);

    @NonNull
    protected static ErrorEntry applyResolvedCause(@NonNull ErrorEntry baseEntry,
                                                   @NonNull ResolvedRootCause resolved) {
        return applyResolvedCauseChain(baseEntry, List.of(resolved));
    }

    @NonNull
    protected static ErrorEntry applyResolvedCauseChain(@NonNull ErrorEntry baseEntry,
                                                        @NonNull List<ResolvedRootCause> chain) {
        ResolvedRootCause first = chain.getFirst();
        Throwable rootCause = first.cause();
        String causeMachineCode = ReasonCodeUtils.toReasonCode(rootCause.getClass());

        String humanReadableReason = StringUtils.hasText(first.resolution().message())
                ? first.resolution().message()
                : rootCause.getMessage();

        List<ErrorDetail> mergedDetails = baseEntry.getDetails() != null
                ? new ArrayList<>(baseEntry.getDetails())
                : new ArrayList<>();

        for (ResolvedRootCause resolved : chain) {
            mergedDetails.addAll(resolved.resolution().details());
        }

        Map<String, String> diagnosticMeta = new LinkedHashMap<>();
        diagnosticMeta.put("rootCauseClass", rootCause.getClass().getName());
        if (StringUtils.hasText(humanReadableReason)) {
            diagnosticMeta.put("rootCauseMessage", humanReadableReason);
        }

        ErrorInfoDetail causeInfo = ErrorInfoDetail.builder()
                .reason(causeMachineCode)
                .domain("cause-chain")
                .diagnosticMetadata(Collections.unmodifiableMap(diagnosticMeta))
                .build();
        mergedDetails.add(causeInfo);

        HttpStatus causeStatus = first.resolution().httpStatus();
        boolean retryable = RetryabilityResolver.isRetryable(baseEntry, chain, causeStatus);
        if (retryable && !RetryabilityResolver.containsRetryInfo(mergedDetails)) {
            Duration delay = RetryabilityResolver.resolveRetryDelay(baseEntry, chain);
            mergedDetails.add(delay != null ? new RetryInfoDetail(delay) : new RetryInfoDetail());
        }

        HttpStatus effectiveStatus = resolveEffectiveStatus(baseEntry.httpStatusOrNull(), causeStatus);

        return baseEntry.toBuilder()
                .httpStatus(effectiveStatus)
                .reason(humanReadableReason)
                .details(Collections.unmodifiableList(mergedDetails))
                .build();
    }

    @NonNull
    private static HttpStatus resolveEffectiveStatus(@Nullable HttpStatus baseStatus,
                                                     @NonNull HttpStatus causeStatus) {
        if (baseStatus == null) {
            return causeStatus;
        }

        if (!baseStatus.is5xxServerError() && causeStatus.is5xxServerError()) {
            return causeStatus;
        }

        return baseStatus;
    }

    @NonNull
    protected static ErrorEntry.ErrorEntryBuilder baseEntry(@NonNull HttpStatus status,
                                                            @NonNull String errorCode,
                                                            @NonNull String message,
                                                            @NonNull ErrorHandlingContext context) {
        return ErrorEntry.builder()
                .httpStatus(status)
                .errorCode(errorCode)
                .message(message)
                .requestURI(context.getRequestPath());
    }
}
