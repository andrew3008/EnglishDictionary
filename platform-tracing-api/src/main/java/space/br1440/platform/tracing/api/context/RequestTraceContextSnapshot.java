package space.br1440.platform.tracing.api.context;

import jakarta.annotation.Nullable;

/**
 * Снимок инфраструктурного контекста запроса для интеграции с платформенным error-handling.
 * <p>
 * Содержит идентификаторы корреляции и активного OpenTelemetry-span'а в том виде, в каком их
 * видит модуль трассировки. Модель ошибок ({@code RequestContext} в {@code web-error-model})
 * строится отдельно в error-handling-стартере (маппинг поле-в-поле), чтобы модуль
 * {@code platform-tracing-spring-boot-autoconfigure} не зависел от артефакта {@code web-error-model}.
 * <p>
 * При сбое трассировки или MDC возвращается снимок с {@code null} в соответствующих полях.
 */
public record RequestTraceContextSnapshot(
        @Nullable String correlationId,
        @Nullable String traceId,
        @Nullable String spanId) {
}
