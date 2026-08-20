package space.br1440.platform.logging.mdc;

import lombok.experimental.UtilityClass;

/**
 * Канонические имена MDC-ключей трассировки для интеграции с {@code platform-tracing} v1.
 * <p>
 * Logging-стартер <b>не записывает</b> эти ключи. Application-visible {@code MDC.get()} заполняет
 * platform-tracing scope-scoped проекция. OTel Agent {@code logback-mdc} обогащает только logging event
 * теми же каноническими именами и <b>не гарантирует</b> {@code MDC.get()}.
 * Стартер только <b>читает</b> ключи из MDC при формировании лог-записи ({@code %maskedMDC} в текстовом паттерне,
 * поля MDC в {@code LogstashEncoder} для JSON).
 * <p>
 * Имена {@code traceId}, {@code spanId}, {@code traceFlags} выровнены с
 * {@code space.br1440.platform.tracing.api.mdc.TracingMdcKeys} и конвенциями Micrometer Tracing /
 * W3C Trace Context. Циклическая зависимость на {@code platform-tracing-api} намеренно
 * <b>не</b> добавляется — дублирование строковых литералов здесь допустимо как контракт между
 * командами.
 * <p>
 * Устаревшие snake_case-ключи ({@code trace_id}, {@code span_id}, {@code trace_flags}) в v1
 * не поддерживаются.
 */
@UtilityClass
public final class TracingMdcLogKeys {

    /** Идентификатор trace (32 hex-символа). */
    public static final String TRACE_ID = "traceId";

    /** Идентификатор активного span (16 hex-символов). */
    public static final String SPAN_ID = "spanId";

    /** W3C trace flags (2 hex-символа). */
    public static final String TRACE_FLAGS = "traceFlags";

}
