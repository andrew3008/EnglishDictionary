package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import space.br1440.platform.tracing.api.span.SpanCategory;

/**
 * Контейнер самонаблюдательных метрик платформенного модуля трассировки.
 * <p>
 * Метрики регистрируются в общем для платформы {@link MeterRegistry}, который поставляется модулем
 * {@code spring-boot-starter-platform-metrics}. Совмещение этих метрик с метриками приложения
 * обеспечивает единый канал наблюдаемости и не требует отдельной инфраструктуры.
 * <p>
 * Имена метрик зафиксированы в архитектурном плане (раздел «Самонаблюдательные метрики стартера»).
 * Они являются стабильным контрактом для дашбордов и алертов SRE; изменение имен — breaking change.
 * <p>
 * <b>Контракт инкремента (Фаза 2 — Wave 3).</b> Методы {@link #incrementScopeClosed},
 * {@link #incrementScopeDoubleClose} и {@link #incrementWatchdogForcedClosed} объявлены как
 * стабильный API; их вызов из core-модуля (OtelSpanScope) и из otel-extension
 * (SpanWatchdogProcessor) подключается через отдельный bridge-bean
 * {@code PlatformTracingMetricsBridge} (см. backlog G2-11-impl). Имена и теги метрик
 * замораживаются текущим релизом — изменения после v0.1.0 рассматриваются как breaking.
 */
public class PlatformTracingMetrics {

    public static final String SPANS_STARTED = "platform_tracing_spans_started_total";
    public static final String EXCEPTIONS_RECORDED = "platform_tracing_exceptions_recorded_total";

    /**
     * Счётчик корректных закрытий {@code SpanScope.close()} (включая идемпотентные повторные
     * закрытия, обработанные внутри scope). Соответствует количеству финализированных span'ов
     * по платформенному фасаду; полезен для соотнесения с {@link #SPANS_STARTED} —
     * расхождение указывает на утечки незакрытых scope'ов или прямую работу с OTel API в обход.
     */
    public static final String SCOPE_CLOSED_TOTAL = "platform_tracing_scope_closed_total";

    /**
     * Счётчик повторных вызовов {@code SpanScope.close()} на уже закрытом scope. Подсветка
     * программных ошибок: повторное закрытие не нарушает контракт ({@link AutoCloseable}
     * идемпотентен), но рост этой метрики указывает на двойное управление scope'ом
     * (например, manual close + try-with-resources) — типичный источник трудно-отлавливаемых
     * багов жизненного цикла span'а.
     */
    public static final String SCOPE_DOUBLE_CLOSE_TOTAL = "platform_tracing_scope_double_close_total";

    /**
     * Счётчик принудительно закрытых span'ов через {@code SpanWatchdogProcessor} (timeout
     * по span'у или трассе). Алертится SRE: рост означает регрессию приложений, забывающих
     * закрыть span (утечка ThreadLocal-контекста), или реально long-running операции,
     * выходящие за лимиты {@code TracingProperties.limits.span-timeout} /
     * {@code trace-timeout}.
     */
    public static final String WATCHDOG_FORCED_CLOSED_TOTAL = "platform_tracing_watchdog_forced_closed_total";

    private final MeterRegistry meterRegistry;

    public PlatformTracingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Увеличивает счётчик стартующих span'ов с тэгом категории.
     * <p>
     * До Slice 1B счётчик инкрементировался декоратором {@code MeteredPlatformTracing} на каждый
     * вызов {@code startSpan}. После Slice 1B публичный фасадный декоратор удалён; durable
     * manual-tracing metrics вернутся через {@code MeteredTracingRuntime} на границе
     * {@code TracingRuntime} (Slice 2/6).
     */
    public void incrementSpansStarted(SpanCategory category) {
        Counter.builder(SPANS_STARTED)
                .description("Количество стартующих span'ов, инициированных платформенным фасадом")
                .tag("category", category.value())
                .register(meterRegistry)
                .increment();
    }

    /**
     * Увеличивает счётчик зарегистрированных исключений.
     * <p>
     * Метрика без тэгов: каждый вызов {@code recordException} с непустым {@link Throwable}
     * по определению означает зафиксированный сбой, и единственное полезное значение
     * любого тэга result было бы константой. Дополнительная декомпозиция выполняется
     * на уровне span-атрибутов и backend'а трассировки, не на уровне метрики.
     */
    public void incrementExceptionsRecorded() {
        Counter.builder(EXCEPTIONS_RECORDED)
                .description("Количество исключений, зафиксированных на span'ах через платформенный фасад")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Увеличивает счётчик корректных закрытий {@code SpanScope}. Вызывается из
     * {@code OtelSpanScope.close()} при первом закрытии — повторные закрытия учитываются
     * в {@link #incrementScopeDoubleClose()}.
     */
    public void incrementScopeClosed() {
        Counter.builder(SCOPE_CLOSED_TOTAL)
                .description("Количество корректно закрытых SpanScope (первый close)")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Увеличивает счётчик повторных закрытий уже закрытого {@code SpanScope}. Вызывается
     * из {@code OtelSpanScope.close()} при попытке закрыть scope, у которого {@code closed=true}.
     */
    public void incrementScopeDoubleClose() {
        Counter.builder(SCOPE_DOUBLE_CLOSE_TOTAL)
                .description("Количество повторных вызовов close() на уже закрытом SpanScope "
                        + "(индикатор багов жизненного цикла)")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Увеличивает счётчик принудительных закрытий span'ов watchdog'ом. Вызывается из
     * {@code SpanWatchdogProcessor} (через bridge, регистрируемый в
     * {@code TracingMetricsAutoConfiguration}, когда watchdog активен).
     *
     * @param reason причина срабатывания: {@code "span-timeout"} или {@code "trace-timeout"}
     */
    public void incrementWatchdogForcedClosed(String reason) {
        Counter.builder(WATCHDOG_FORCED_CLOSED_TOTAL)
                .description("Количество принудительно закрытых span'ов watchdog'ом по timeout")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}
