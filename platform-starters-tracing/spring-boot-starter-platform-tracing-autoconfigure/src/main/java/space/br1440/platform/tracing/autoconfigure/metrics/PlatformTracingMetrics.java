package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.EnumMap;
import java.util.Objects;

import space.br1440.platform.tracing.api.span.SpanCategory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

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

    private final EnumMap<SpanCategory, Counter> spansStartedCounters;
    private final Counter exceptionsRecordedCounter;
    private final Counter scopeClosedCounter;
    private final Counter scopeDoubleCloseCounter;

    public PlatformTracingMetrics(MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry");

        spansStartedCounters = new EnumMap<>(SpanCategory.class);
        for (SpanCategory category : SpanCategory.values()) {
            Counter counter = Counter.builder(SPANS_STARTED)
                    .description("Number of spans started, initiated by the platform facade")
                    .tag("category", category.value())
                    .register(meterRegistry);
            spansStartedCounters.put(category, counter);
        }

        exceptionsRecordedCounter = Counter.builder(EXCEPTIONS_RECORDED)
                .description("Number of exceptions recorded on spans via the platform facade")
                .register(meterRegistry);

        scopeClosedCounter = Counter.builder(SCOPE_CLOSED_TOTAL)
                .description("Number of span handles closed correctly (first close)")
                .register(meterRegistry);

        scopeDoubleCloseCounter = Counter.builder(SCOPE_DOUBLE_CLOSE_TOTAL)
                .description("Number of repeated close() calls on an already-closed span handle (indicator of lifecycle bugs)")
                .register(meterRegistry);
    }

    public void incrementSpansStarted(SpanCategory category) {
        spansStartedCounters.get(Objects.requireNonNull(category, "category")).increment();
    }

    public void incrementExceptionsRecorded() {
        exceptionsRecordedCounter.increment();
    }

    public void incrementScopeClosed() {
        scopeClosedCounter.increment();
    }

    public void incrementScopeDoubleClose() {
        scopeDoubleCloseCounter.increment();
    }
}
