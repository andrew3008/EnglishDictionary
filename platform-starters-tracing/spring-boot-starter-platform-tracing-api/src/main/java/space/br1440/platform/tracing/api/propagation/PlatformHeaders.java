package space.br1440.platform.tracing.api.propagation;

import java.util.List;

import lombok.experimental.UtilityClass;

/**
 * HTTP-заголовки платформенного контракта, используемые при передаче и управлении trace-контекстом.
 * Имена {@code X-Trace-On}, {@code X-QA-Trace}, {@code X-Request-Id} и {@code X-Correlation-Id} фиксированы.
 * <p>
 * Стандартный заголовок W3C {@code traceparent} (и сопутствующие) обрабатывается
 * стандартными propagator'ами OpenTelemetry и не требует отдельных констант.
 */
@UtilityClass
public final class PlatformHeaders {

    /**
     * Управляющий заголовок принудительной записи trace'а. Если значение распознано как «истинное»
     * (см. Agent Category B {@code force-record-values}),
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
     * Edge-stable request id. Это platform-owned authoritative заголовок ответа:
     * приложение не вправе его переопределять, пока
     * {@code platform.tracing.request-id-header.enabled} остаётся {@code true}.
     * <p>
     * Используется исключительно как request identity, а НЕ как замена для {@code trace_id}
     * распределённой трассировки (для трассировки применяется W3C {@code traceparent}).
     * Значение данного заголовка не нужно использовать для идентификации span'а и
     * как уникальное значение при retry одного и того же запроса.
     */
    public static final String X_REQUEST_ID = "X-Request-Id";

    /**
     * Единственная wire-константа business correlationId — используется и inbound-, и
     * outbound-стороной {@code tracing}, а также {@code error-handling} как потребителем
     * (см. {@code adr-correlation-ownership.md}). Имя заголовка не конфигурируется:
     * поведенческие переключатели {@code accept-inbound-correlation-id} /
     * {@code propagate-correlation-id} по умолчанию выключены (fail-closed), но само имя
     * заголовка фиксировано, чтобы не расходиться между модулями.
     */
    public static final String X_CORRELATION_ID = "X-Correlation-Id";

    /**
     * Имена, которыми владеет активный outbound-адаптер независимо от разрешённых policy слотов.
     */
    public static final List<String> OUTBOUND_CONTROL_HEADERS =
            List.of(X_TRACE_ON, X_QA_TRACE, X_REQUEST_ID, X_CORRELATION_ID);

    /**
     * Platform-owned authoritative заголовок ответа с {@code traceId} текущего span'а.
     * Приложение не вправе его переопределять. Семантика одинакова в Servlet и WebFlux;
     * механизм установки может отличаться.
     * <p>
     * Устанавливается и защищается только при наличии валидного span context —
     * никогда не выставляется как нулевое/пустое значение.
     * Отсутствие данного заголовка является признаком того, что нет активного trace.
     */
    public static final String X_TRACE_ID = "X-Trace-Id";

}
