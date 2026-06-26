package space.br1440.platform.errorhandling.extension.base;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;

import java.util.List;
import java.util.Objects;

public abstract class AbstractRootCauseExceptionStrategy implements RootCauseExceptionHandlingStrategy {

    private final int order;

    protected final StackHintExtractor stackHintExtractor;

    protected AbstractRootCauseExceptionStrategy(int order, @NonNull StackHintExtractor stackHintExtractor) {
        if (order <= 0) {
            throw new IllegalArgumentException("order must be positive, got: " + order);
        }

        this.order = order;
        this.stackHintExtractor = Objects.requireNonNull(stackHintExtractor, "stackHintExtractor must not be null");
    }

    @Override
    public int getOrder() {
        return order;
    }

    @NonNull
    protected final DiagnosticMetadataBuilder diagnosticMetadataFor(@NonNull Throwable cause) {
        return DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);
    }

    @NonNull
    protected RootCauseResolution resolution(@NonNull HttpStatus httpStatus,
                                             @NonNull String message,
                                             @NonNull List<ErrorDetail> details) {
        return RootCauseResolution.of(httpStatus, message, details);
    }
}
