package space.br1440.platform.errorhandling.core.strategy.top.mapping;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Map;

public record ExceptionMapping(HttpStatus httpStatus,
                               String reason,
                               String message,
                               @Nullable String granularReason,
                               Map<String, String> clientMetadata) {

    @NonNull
    public static ExceptionMapping of(@NonNull HttpStatus httpStatus,
                                      @NonNull String reason,
                                      @NonNull String message,
                                      @Nullable String granularReason,
                                      @NonNull Map<String, String> clientMetadata) {
        return new ExceptionMapping(httpStatus, reason, message, granularReason, clientMetadata);
    }
}
