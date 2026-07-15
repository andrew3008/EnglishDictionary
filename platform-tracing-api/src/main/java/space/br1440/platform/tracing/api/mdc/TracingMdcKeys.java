package space.br1440.platform.tracing.api.mdc;

import lombok.experimental.UtilityClass;

/**
 * Канонические имена ключей MDC, используемые при интеграции с {@code spring-boot-starter-platform-logging}
 * и платформенным error-handling.
 * <p>
 * Имена {@code traceId}, {@code spanId}, {@code traceFlags} выровнены с конвенциями
 * {@code micrometer-tracing} и {@code micrometer-tracing-bridge-otel} (см. {@code OtelCurrentTraceContext}),
 * чтобы обеспечить совместимость со стандартным logback-pattern'ом Spring Boot 3.x вида
 * {@code %X{traceId} %X{spanId}} и не требовать кастомного формата на стороне logging-стартера.
 * <p>
 * Корреляционный идентификатор {@code correlation_id} оставлен в snake_case для совместимости
 * с {@code ErrorHandlingMdcKeys#CORRELATION_ID} платформенного стартера.
 * <p>
 * Единый источник правды для трассировки и согласованного с ним корреляционного идентификатора в логах:
 * прикладной код и стартеры ссылаются только на константы этого класса (или на реэкспорт в {@code ErrorHandlingMdcKeys}),
 * без дублирования строковых литералов.
 */
@UtilityClass
public final class TracingMdcKeys {

    /** Идентификатор trace'а в шестнадцатеричной форме (32 символа). */
    public static final String TRACE_ID = "traceId";

    /** Идентификатор активного span'а в шестнадцатеричной форме (16 символов). */
    public static final String SPAN_ID = "spanId";

    /** Флаги trace context (W3C trace flags), 2 шестнадцатеричных символа. */
    public static final String TRACE_FLAGS = "traceFlags";

    /**
     * Корреляционный идентификатор запроса в MDC (snake_case). Должен совпадать с
     * {@code space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys#CORRELATION_ID}
     * на стороне error-handling-стартеров.
     */
    public static final String CORRELATION_ID = "correlation_id";

    /**
     * Логическое имя upstream-сервиса, вызов которого завершился ошибкой в рамках текущего запроса.
     * Заполняется {@link space.br1440.platform.tracing.core.mdc.remote.RemoteServiceMdc} из {@code EnrichingSpanProcessor} при завершении
     * CLIENT-span'а со статусом ERROR. Очищается в HTTP-фильтрах запроса.
     * Имя совпадает со значением одноимённого платформенного атрибута
     * {@code space.br1440.platform.tracing.api.attributes.PlatformAttributes#PLATFORM_REMOTE_SERVICE}.
     */
    public static final String REMOTE_SERVICE = "platform.remote.service";

}
