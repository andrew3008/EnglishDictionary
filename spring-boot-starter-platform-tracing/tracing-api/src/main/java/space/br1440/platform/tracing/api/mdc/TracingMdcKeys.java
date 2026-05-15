package space.br1440.platform.tracing.api.mdc;

import lombok.experimental.UtilityClass;

/**
 * Канонические имена ключей MDC, используемые при интеграции с {@code spring-boot-starter-platform-logging}
 * и платформенным error-handling.
 * <p>
 * Имена ключей выбраны без префикса {@code platform.} и в snake_case для соответствия принятой в
 * существующих платформенных стартерах конвенции (см. {@code DEFAULT_MDC_KEYS_TO_EXCLUDE} в
 * {@code LoggingProperties} соседнего проекта).
 * <p>
 * Единый источник правды для трассировки и согласованного с ним корреляционного идентификатора в логах:
 * прикладной код и стартеры ссылаются только на константы этого класса (или на реэкспорт в
 * {@code ErrorHandlingMdcKeys}), без дублирования строковых литералов.
 */
@UtilityClass
public final class TracingMdcKeys {

    /** Идентификатор trace'а в шестнадцатеричной форме (32 символа). */
    public static final String TRACE_ID = "trace_id";

    /** Идентификатор активного span'а в шестнадцатеричной форме (16 символов). */
    public static final String SPAN_ID = "span_id";

    /** Флаги trace context (W3C trace flags), 2 шестнадцатеричных символа. */
    public static final String TRACE_FLAGS = "trace_flags";

    /**
     * Корреляционный идентификатор запроса в MDC (snake_case). Должен совпадать с
     * {@code space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys#CORRELATION_ID}
     * на стороне error-handling-стартеров.
     */
    public static final String CORRELATION_ID = "correlation_id";

    /**
     * Логическое имя upstream-сервиса, вызов которого завершился ошибкой в рамках текущего запроса.
     * Заполняется {@code EnrichingSpanProcessor} при завершении клиентского span'а со статусом ERROR.
     * Имя совпадает со значением одноимённого платформенного атрибута
     * {@code space.br1440.platform.tracing.api.attributes.PlatformAttributes#PLATFORM_REMOTE_SERVICE}.
     * <p>
     * <b>Особое поведение в {@code TracingMdcContextWrapper}:</b> ключ имеет sticky-семантику.
     * Запись внутри клиентского {@code io.opentelemetry.context.Scope} не откатывается при его
     * закрытии — значение становится видимым на родительском (серверном) span'е и доходит до
     * {@code @ControllerAdvice}. На старте нового серверного span'а ключ обнуляется во избежание
     * утечки между запросами.
     */
    public static final String REMOTE_SERVICE = "platform.remote.service";
}
