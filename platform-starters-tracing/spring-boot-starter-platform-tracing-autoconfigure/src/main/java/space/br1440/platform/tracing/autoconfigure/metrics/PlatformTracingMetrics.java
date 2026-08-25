package space.br1440.platform.tracing.autoconfigure.metrics;

import space.br1440.platform.tracing.api.span.SpanCategory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PlatformTracingMetrics {

    public static final String SPANS_STARTED = "platform_tracing_spans_started_total";
    public static final String EXCEPTIONS_RECORDED = "platform_tracing_exceptions_recorded_total";

    /**
     * Счётчик корректных закрытий {@code span handle.close()} (включая идемпотентные повторные
     * закрытия, обработанные внутри scope). Соответствует количеству финализированных span'ов
     * по платформенному фасаду; полезен для соотнесения с {@link #SPANS_STARTED} —
     * расхождение указывает на утечки незакрытых scope'ов или прямую работу с OTel API в обход.
     */
    public static final String SCOPE_CLOSED_TOTAL = "platform_tracing_scope_closed_total";

    /**
     * Счётчик повторных вызовов {@code span handle.close()} на уже закрытом scope. Подсветка
     * программных ошибок: повторное закрытие не нарушает контракт ({@link AutoCloseable}
     * идемпотентен), но рост этой метрики указывает на двойное управление scope'ом
     * (например, manual close + try-with-resources) — типичный источник трудно-отлавливаемых
     * багов жизненного цикла span'а.
     */
    public static final String SCOPE_DOUBLE_CLOSE_TOTAL = "platform_tracing_scope_double_close_total";

    private final MeterRegistry meterRegistry;

    public void incrementSpansStarted(SpanCategory category) {
        Counter.builder(SPANS_STARTED)
                .description("Number of spans started, initiated by the platform facade")
                .tag("category", category.value())
                .register(meterRegistry)
                .increment();
    }

    public void incrementExceptionsRecorded() {
        Counter.builder(EXCEPTIONS_RECORDED)
                .description("Number of exceptions recorded on spans via the platform facade")
                .register(meterRegistry)
                .increment();
    }

    public void incrementScopeClosed() {
        Counter.builder(SCOPE_CLOSED_TOTAL)
                .description("Number of span handles closed correctly (first close)")
                .register(meterRegistry)
                .increment();
    }

    public void incrementScopeDoubleClose() {
        Counter.builder(SCOPE_DOUBLE_CLOSE_TOTAL)
                .description("Number of repeated close() calls on an already-closed span handle (indicator of lifecycle bugs)")
                .register(meterRegistry)
                .increment();
    }
}
