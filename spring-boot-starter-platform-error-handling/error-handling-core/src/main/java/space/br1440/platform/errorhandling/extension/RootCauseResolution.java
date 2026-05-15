package space.br1440.platform.errorhandling.extension;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;

import java.util.List;
import java.util.Objects;

/**
 * Результат анализа root-cause исключения стратегией {@link RootCauseExceptionHandlingStrategy}.
 *
 * <p>Содержит полный набор данных, необходимых для формирования HTTP-ответа
 * и метрик: HTTP-статус, сообщение, детали ошибки и признак повторяемости операции.
 *
 * @see RootCauseExceptionHandlingStrategy
 * @see Retryability
 */
public record RootCauseResolution(@NonNull HttpStatus httpStatus,
                                  @NonNull String message,
                                  @NonNull List<ErrorDetail> details,
                                  @NonNull Retryability retryability) {

    public RootCauseResolution {
        Objects.requireNonNull(httpStatus, "httpStatus must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(details, "details must not be null");
        Objects.requireNonNull(retryability, "retryability must not be null");

        details = List.copyOf(details);
    }

    @NonNull
    public static RootCauseResolution of(@NonNull HttpStatus httpStatus) {
        return new RootCauseResolution(httpStatus, "", List.of(), Retryability.UNSPECIFIED);
    }

    @NonNull
    public static RootCauseResolution of(@NonNull HttpStatus httpStatus,
                                         @NonNull String message,
                                         @NonNull List<ErrorDetail> details) {
        return new RootCauseResolution(httpStatus, message, details, Retryability.UNSPECIFIED);
    }

    @NonNull
    public static RootCauseResolution of(@NonNull HttpStatus httpStatus,
                                         @NonNull String message,
                                         @NonNull List<ErrorDetail> details,
                                         @NonNull Retryability retryability) {
        return new RootCauseResolution(httpStatus, message, details, retryability);
    }

    @NonNull
    public static RootCauseResolution retryable(@NonNull HttpStatus httpStatus,
                                                @NonNull String message,
                                                @NonNull List<ErrorDetail> details) {
        return new RootCauseResolution(httpStatus, message, details, Retryability.RETRYABLE);
    }

    @NonNull
    public static RootCauseResolution notRetryable(@NonNull HttpStatus httpStatus,
                                                   @NonNull String message,
                                                   @NonNull List<ErrorDetail> details) {
        return new RootCauseResolution(httpStatus, message, details, Retryability.NOT_RETRYABLE);
    }
}
