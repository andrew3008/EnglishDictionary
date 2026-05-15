package space.br1440.platform.errorhandling.core.constants;

import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

/**
 * Ключи MDC для структурированных логов error-handling pipeline.
 * <p>
 * {@link #CORRELATION_ID} совпадает с {@link TracingMdcKeys#CORRELATION_ID} — единая каноника для
 * корреляционного идентификатора в логах (snake_case).
 */
public final class ErrorHandlingMdcKeys {

    /** Корреляционный идентификатор запроса (MDC, snake_case). Реэкспорт из {@link TracingMdcKeys}. */
    public static final String CORRELATION_ID = TracingMdcKeys.CORRELATION_ID;

    /** Полное квалифицированное имя класса перехваченного исключения. */
    public static final String EXCEPTION_TYPE = "exceptionType";

    /** Разрешённый HTTP-статус ответа. */
    public static final String ERROR_STATUS = "error_status";

    /** Платформенный errorCode (например, {@code VALIDATION}, {@code UNKNOWN_INTERNAL_ERROR}). */
    public static final String ERROR_CODE = "error_code";

    /** Машиночитаемая reason-метка (опциональна). */
    public static final String ERROR_REASON = "error_reason";

    /** Публичное человеко-читаемое сообщение об ошибке. */
    public static final String ERROR_MESSAGE = "error_message";

    /** URI запроса (без query) — для structured-фильтрации в Kibana/Datadog без regex по message. */
    public static final String REQUEST_URI = "request_uri";

    private ErrorHandlingMdcKeys() {
        throw new AssertionError("ErrorHandlingMdcKeys — utility-класс, инстанцирование запрещено.");
    }
}
