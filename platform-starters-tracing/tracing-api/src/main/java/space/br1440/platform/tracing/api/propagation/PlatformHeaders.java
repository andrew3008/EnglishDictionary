package space.br1440.platform.tracing.api.propagation;

/**
 * HTTP-заголовки платформенного стандарта, используемые при передаче и управлении trace-контекстом.
 * <p>
 * Стандартный заголовок W3C {@code traceparent} (и сопутствующие) обрабатывается стандартными propagator'ами
 * OpenTelemetry и не требует отдельных констант. В этом классе фиксируются только заголовки,
 * специфичные для платформенного контракта.
 */
public final class PlatformHeaders {

    private PlatformHeaders() {
        // utility-класс
    }

    /**
     * Управляющий заголовок принудительной записи trace'а. Если значение распознано как «истинное»
     * (см. конфигурацию {@code platform.tracing.sampling.force-record-header-values}),
     * trace будет записан вне зависимости от вероятностного сэмплирования.
     */
    public static final String X_TRACE_ON = "X-Trace-On";

    /**
     * Маркерный заголовок QA-инфраструктуры, гарантирующий 100% запись trace'а независимо от sampler'а.
     * Используется автоматизированными тестами.
     */
    public static final String X_QA_TRACE = "X-QA-Trace";

    /**
     * Идентификатор запроса, возвращаемый в ответе для удобства службы поддержки.
     * Значение совпадает с {@code traceId} текущего span'а в шестнадцатеричной форме.
     */
    public static final String X_REQUEST_ID = "X-Request-Id";

    /**
     * Расширенный заголовок с идентификатором trace'а в ответе. Дублирует {@link #X_REQUEST_ID},
     * но имеет специфическое имя для интеграций, ожидающих именно его.
     */
    public static final String X_TRACE_ID = "X-Trace-Id";
}
