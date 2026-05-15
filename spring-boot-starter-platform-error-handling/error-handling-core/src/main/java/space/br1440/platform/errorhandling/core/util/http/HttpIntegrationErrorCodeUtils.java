package space.br1440.platform.errorhandling.core.util.http;

import lombok.experimental.UtilityClass;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;

@UtilityClass
public final class HttpIntegrationErrorCodeUtils {

    @NonNull
    public static String determineErrorCode(@NonNull HttpStatus httpStatus) {
        int httpStatusCode = httpStatus.value();
        return switch (httpStatusCode) {
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "PERMISSION_DENIED";
            case 404 -> "NOT_FOUND";
            case 410 -> "GONE";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "UNAVAILABLE";
            case 504 -> "DEADLINE_EXCEEDED";
            default -> "INTERNAL";
        };
    }

}
