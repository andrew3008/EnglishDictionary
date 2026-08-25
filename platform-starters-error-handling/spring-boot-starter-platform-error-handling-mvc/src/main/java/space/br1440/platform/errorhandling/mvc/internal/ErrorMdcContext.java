package space.br1440.platform.errorhandling.mvc.internal;

import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.model.ErrorEntry;

public final class ErrorMdcContext implements AutoCloseable {

    private static final String[] OWN_KEYS = {
            ErrorHandlingMdcKeys.EXCEPTION_TYPE,
            ErrorHandlingMdcKeys.ERROR_STATUS,
            ErrorHandlingMdcKeys.ERROR_CODE,
            ErrorHandlingMdcKeys.ERROR_REASON,
            ErrorHandlingMdcKeys.ERROR_MESSAGE,
            ErrorHandlingMdcKeys.REQUEST_URI
    };

    private final boolean manageCorrelationId;

    private ErrorMdcContext(boolean manageCorrelationId) {
        this.manageCorrelationId = manageCorrelationId;
    }

    @NonNull
    public static ErrorMdcContext open(@NonNull String correlationId, @NonNull Throwable exception) {
        return open(correlationId, exception, true);
    }

    @NonNull
    public static ErrorMdcContext open(@NonNull String correlationId, @NonNull Throwable exception, boolean manageCorrelationId) {
        ErrorMdcContext ctx = new ErrorMdcContext(manageCorrelationId);
        if (manageCorrelationId) {
            putSafe(ErrorHandlingMdcKeys.CORRELATION_ID, correlationId);
        }

        putSafe(ErrorHandlingMdcKeys.EXCEPTION_TYPE, exception.getClass().getName());
        return ctx;
    }

    public void enrichWithEntry(@NonNull ErrorEntry entry) {
        putSafe(ErrorHandlingMdcKeys.ERROR_STATUS, String.valueOf(entry.getHttpStatus().value()));
        putSafe(ErrorHandlingMdcKeys.ERROR_CODE, entry.getErrorCode());
        putSafe(ErrorHandlingMdcKeys.ERROR_MESSAGE, entry.getMessage());

        if (StringUtils.hasText(entry.getReason())) {
            putSafe(ErrorHandlingMdcKeys.ERROR_REASON, entry.getReason());
        } else {
            MDC.remove(ErrorHandlingMdcKeys.ERROR_REASON);
        }

        if (StringUtils.hasText(entry.getRequestURI())) {
            putSafe(ErrorHandlingMdcKeys.REQUEST_URI, entry.getRequestURI());
        } else {
            MDC.remove(ErrorHandlingMdcKeys.REQUEST_URI);
        }
    }

    public static void putSafe(@NonNull String key, @Nullable String value) {
        if (value == null) {
            return;
        }

        MDC.put(key, value);
    }

    @Override
    public void close() {
        if (manageCorrelationId) {
            MDC.remove(ErrorHandlingMdcKeys.CORRELATION_ID);
        }

        for (String key : OWN_KEYS) {
            MDC.remove(key);
        }
    }
}
