package space.br1440.platform.errorhandling.utils;

import org.springframework.http.HttpStatus;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Канонические строковые коды ошибок платформенного слоя (wire / {@code ErrorEntry#errorCode}).
 */
@UtilityClass
public final class CanonicalErrors {

    /** Неклассифицированное внутреннее исключение (fallback обработчика «unknown»). */
    public static final String UNKNOWN_INTERNAL_ERROR = "UNKNOWN_INTERNAL_ERROR";

    /** Обобщённый код клиентских / валидационных ошибок. */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /** Переданный аргумент некорректен (400, 422). Соответствует {@code google.rpc.Code.INVALID_ARGUMENT}. */
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";

    /** Клиент не аутентифицирован (401). Соответствует {@code google.rpc.Code.UNAUTHENTICATED}. */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    /** Доступ запрещён (403). Соответствует {@code google.rpc.Code.PERMISSION_DENIED}. */
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";

    /** Ресурс не найден (404, 410). Соответствует {@code google.rpc.Code.NOT_FOUND}. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** Конфликт состояния (409). Соответствует {@code google.rpc.Code.ABORTED}. */
    public static final String CONFLICT = "CONFLICT";

    /** Исчерпан лимит (429). Соответствует {@code google.rpc.Code.RESOURCE_EXHAUSTED}. */
    public static final String RESOURCE_EXHAUSTED = "RESOURCE_EXHAUSTED";

    /** Внутренняя ошибка сервера (500). Соответствует {@code google.rpc.Code.INTERNAL}. */
    public static final String INTERNAL = "INTERNAL";

    /**
     * Ошибка шлюза — upstream-сервис вернул невалидный ответ (502).
     *
     * <p>Семантически отличается от {@link #INTERNAL}: 502 указывает на проблему
     * в upstream-интеграции (Feign, WebClient, gRPC proxy), а не на внутреннюю ошибку
     * текущего сервиса. Соответствует {@code google.rpc.Code.UNAVAILABLE} в контексте
     * транзитивного сбоя зависимости.
     */
    public static final String BAD_GATEWAY = "BAD_GATEWAY";

    /** Сервис недоступен (503). Соответствует {@code google.rpc.Code.UNAVAILABLE}. */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /** Превышен дедлайн (504). Соответствует {@code google.rpc.Code.DEADLINE_EXCEEDED}. */
    public static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";

    /**
     * Маппинг {@link HttpStatus} → строковый код ошибки по семантике Google AIP-193 / google.rpc.Code.
     *
     * <p>Используется как единая точка маппинга в стратегиях, где статус известен заранее
     * (например, {@code ResponseStatusExceptionStrategy}, {@code DefaultUnknownExceptionHandler}).
     *
     * <p><b>Catch-all поведение:</b> статусы, не перечисленные явно в {@code switch},
     * коллапсируют по классу:
     * <ul>
     *   <li>Любой 4xx → {@link #INVALID_ARGUMENT}</li>
     *   <li>Любой 5xx (и прочие, включая 1xx/2xx/3xx, которые на практике не возникают
     *       в error handling pipeline) → {@link #INTERNAL}</li>
     * </ul>
     * Это намеренное упрощение: добавлять маппинг для каждого из ~60 HTTP-статусов
     * нецелесообразно, поскольку стратегии сами задают точный код для конкретных сценариев.
     * Поведение зафиксировано тестом {@code CanonicalErrorCodesTest.FromHttpStatus}.
     */
    @NotNull
    public static String resolveErrorCode(@NotNull HttpStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        return switch (status) {
            case BAD_REQUEST               -> INVALID_ARGUMENT;
            case UNAUTHORIZED              -> UNAUTHENTICATED;
            case FORBIDDEN                 -> PERMISSION_DENIED;
            case NOT_FOUND, GONE           -> NOT_FOUND;
            case CONFLICT                  -> CONFLICT;
            case UNPROCESSABLE_ENTITY      -> INVALID_ARGUMENT;
            case TOO_MANY_REQUESTS         -> RESOURCE_EXHAUSTED;
            case INTERNAL_SERVER_ERROR     -> INTERNAL;
            case BAD_GATEWAY               -> BAD_GATEWAY;
            case SERVICE_UNAVAILABLE       -> UNAVAILABLE;
            case GATEWAY_TIMEOUT           -> DEADLINE_EXCEEDED;
            default -> status.is4xxClientError() ? INVALID_ARGUMENT : INTERNAL;
        };
    }
}
