package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;

/**
 * Единая таблица соответствия {@link HttpStatus} → канонический {@code errorCode}
 * верхнего уровня DTO ({@code dto.errorCode}). Используется и MVC-, и WebFlux-стратегиями
 * фреймворка, чтобы для эквивалентных HTTP-сценариев в bi-stack приложении (Spring MVC +
 * WebFlux WebClient) клиент получал идентичный {@code errorCode}.
 *
 * <h2>Принципы маппинга</h2>
 * <p>
 * Канон следует gRPC-style {@code google.rpc.Code}-семантике: широкие классы ошибок
 * без излишней дробности на уровне {@code errorCode}. Гранулярные различия (например,
 * {@code METHOD_NOT_ALLOWED} vs {@code UNSUPPORTED_MEDIA_TYPE}) — на уровне
 * {@code ErrorInfoDetail.reason}, что даёт клиентам выбор: читать только {@code errorCode}
 * (стабильно, мало возможных значений) или спускаться в детали при необходимости.
 *
 * <ul>
 *   <li>{@code 400, 405, 406, 415} → {@code INVALID_ARGUMENT}</li>
 *   <li>{@code 401} → {@code UNAUTHENTICATED}</li>
 *   <li>{@code 403} → {@code PERMISSION_DENIED}</li>
 *   <li>{@code 404} → {@code NOT_FOUND}</li>
 *   <li>{@code 409} → {@code ABORTED}</li>
 *   <li>{@code 412} → {@code FAILED_PRECONDITION}</li>
 *   <li>{@code 413} → {@code RESOURCE_EXHAUSTED}</li>
 *   <li>{@code 429} → {@code RESOURCE_EXHAUSTED}</li>
 *   <li>{@code 499} → {@code CANCELLED}</li>
 *   <li>{@code 500} → {@code INTERNAL}</li>
 *   <li>{@code 501} → {@code UNIMPLEMENTED}</li>
 *   <li>{@code 503} → {@code UNAVAILABLE}</li>
 *   <li>{@code 504} → {@code DEADLINE_EXCEEDED}</li>
 *   <li>прочие 4xx → {@code INVALID_ARGUMENT}</li>
 *   <li>прочие 5xx → {@code INTERNAL}</li>
 *   <li>остальное → {@code UNKNOWN}</li>
 * </ul>
 *
 * <p>Ссылки:
 * <a href="https://github.com/grpc/grpc/blob/master/doc/statuscodes.md">gRPC status codes</a>,
 * <a href="https://google.aip.dev/193">AIP-193 Errors</a>.
 */
@UtilityClass
public final class HttpStatusToErrorCodeMapper {

    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ABORTED = "ABORTED";
    public static final String FAILED_PRECONDITION = "FAILED_PRECONDITION";
    public static final String RESOURCE_EXHAUSTED = "RESOURCE_EXHAUSTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String INTERNAL = "INTERNAL";
    public static final String UNIMPLEMENTED = "UNIMPLEMENTED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
    public static final String UNKNOWN = "UNKNOWN";

    @NonNull
    public static String map(@NonNull HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST,
                 METHOD_NOT_ALLOWED,
                 NOT_ACCEPTABLE,
                 UNSUPPORTED_MEDIA_TYPE   -> INVALID_ARGUMENT;
            case UNAUTHORIZED             -> UNAUTHENTICATED;
            case FORBIDDEN                -> PERMISSION_DENIED;
            case NOT_FOUND                -> NOT_FOUND;
            case CONFLICT                 -> ABORTED;
            case PRECONDITION_FAILED      -> FAILED_PRECONDITION;
            case PAYLOAD_TOO_LARGE,
                 TOO_MANY_REQUESTS        -> RESOURCE_EXHAUSTED;
            case INTERNAL_SERVER_ERROR    -> INTERNAL;
            case NOT_IMPLEMENTED          -> UNIMPLEMENTED;
            case SERVICE_UNAVAILABLE      -> UNAVAILABLE;
            case GATEWAY_TIMEOUT          -> DEADLINE_EXCEEDED;
            default -> {
                if (status.value() == 499) {
                    yield CANCELLED;
                }
                if (status.is4xxClientError()) yield INVALID_ARGUMENT;
                if (status.is5xxServerError()) yield INTERNAL;
                yield UNKNOWN;
            }
        };
    }
}
