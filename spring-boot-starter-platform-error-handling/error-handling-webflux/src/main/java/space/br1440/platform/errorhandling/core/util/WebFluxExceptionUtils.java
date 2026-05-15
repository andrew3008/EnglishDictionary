package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import space.br1440.platform.errorhandling.core.util.http.HttpIntegrationErrorCodeUtils;
import space.br1440.platform.errorhandling.core.util.http.RetryAfterHeaderParser;

import java.time.Duration;

@UtilityClass
public final class WebFluxExceptionUtils {

    public static boolean isWebClientException(@NonNull Object obj) {
        return obj instanceof WebClientException;
    }

    public static boolean isResponseException(@NonNull Object obj) {
        return obj instanceof WebClientResponseException;
    }

    public static boolean isRequestException(@NonNull Object obj) {
        return obj instanceof WebClientRequestException;
    }

    public static int extractStatusCode(@NonNull Object exception) {
        if (exception instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            return (status == 0) ? -1 : status;
        }

        return -1;
    }

    @NonNull
    public static HttpStatus mapToHttpStatus(boolean isResponseException,
                                             boolean isRequestException,
                                             int upstreamStatus) {
        if (isRequestException || !isResponseException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        return switch (upstreamStatus) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 410 -> HttpStatus.GONE;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 504 -> HttpStatus.GATEWAY_TIMEOUT;
            default -> {
                if (upstreamStatus >= 400 && upstreamStatus < 600) {
                    yield HttpStatus.BAD_GATEWAY;
                }

                yield HttpStatus.SERVICE_UNAVAILABLE;
            }
        };
    }

    @NonNull
    public static String resolveErrorCategory(boolean isResponseException,
                                              boolean isRequestException) {
        if (isRequestException) {
            return "network";
        }

        if (isResponseException) {
            return "upstream_response";
        }

        return "unknown";
    }

    @NonNull
    public static String determineErrorCode(HttpStatus httpStatus) {
        return HttpIntegrationErrorCodeUtils.determineErrorCode(httpStatus);
    }

    @Nullable
    public static Duration extractRetryAfterDelay(@NonNull Throwable cause) {
        if (cause instanceof WebClientResponseException response) {
            String retryAfter = response.getHeaders().getFirst("Retry-After");
            return RetryAfterHeaderParser.parse(retryAfter);
        }

        return null;
    }
}
