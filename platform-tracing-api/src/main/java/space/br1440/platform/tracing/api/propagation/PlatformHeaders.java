package space.br1440.platform.tracing.api.propagation;

import lombok.experimental.UtilityClass;

/**
 * HTTP-заголовки платформенного контракта, используемые при передаче и управлении trace-контекстом.
 * <p>
 * Стандартный заголовок W3C {@code traceparent} (и сопутствующие) обрабатывается
 * стандартными propagator'ами OpenTelemetry и не требует отдельных констант.
 */
@UtilityClass
public final class PlatformHeaders {

    /**
     * Управляющий заголовок принудительной записи trace'а. Если значение распознано как «истинное»
     * (см. конфигурацию {@code platform.tracing.sampling.force-record-header-values}),
     * trace будет записан без проверки необходимости его сэмплирования.
     */
    public static final String X_TRACE_ON = "X-Trace-On";

    /**
     * Маркерный заголовок QA-инфраструктуры, безусловно форсирующий запись trace'а независимо как от sampler'а,
     * так и от конфигурации принятых значений {@link #X_TRACE_ON}.
     * Используется автоматизированными тестами и ручного тестирования.
     */
    public static final String X_QA_TRACE = "X-QA-Trace";

    /**
     * Edge-stable correlation id для корреляции в логах для того,
     * чтобы привязать response к его сетевому request по log-строкам.
     * <p>
     * Используется исключительно как correlation id, а НЕ как замена для {@code trace_id}
     * распределённой трассировки (для трассировки применяется W3C {@code traceparent}).
     * Значение данного заголовка не нужно использовать для идентификации span'а и
     * как уникальное значение при retry одного и того же запроса.
     */
    public static final String X_REQUEST_ID = "X-Request-Id";

    /**
     * Опциональный boundary bridge для business correlationId.
     * По умолчанию платформа не читает и не пишет этот заголовок: канонический транспорт
     * утверждается отдельно release gate {@code RG-IDENTITY-TRUST}.
     */
    public static final String X_CORRELATION_ID = "X-Correlation-ID";

    /**
     * Заголовок с идентификатором распределённого trace'а в ответе ({@code traceId} текущего span'а).
     * <p>
     * Устанавливается только при наличии валидного span context —
     * никогда не выставляется как нулевое/пустое значение.
     * Отсутствие данного заголовка является признаком того, что нет активного trace.
     */
    public static final String X_TRACE_ID = "X-Trace-Id";

}
