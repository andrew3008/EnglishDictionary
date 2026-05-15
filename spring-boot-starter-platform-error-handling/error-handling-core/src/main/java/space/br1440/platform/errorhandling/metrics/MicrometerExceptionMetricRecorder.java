package space.br1440.platform.errorhandling.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;

import java.time.Duration;

/**
 * Реализация {@link ExceptionMetricRecorder} на основе Micrometer {@link MeterRegistry}.
 *
 * <h2>Метрики</h2>
 * <ul>
 *   <li>{@code exception.handled.total} (Counter) — количество обработанных исключений.
 *       Теги: {@code strategy}, {@code http_status_class}, {@code exception_type}.</li>
 *   <li>{@code exception.handling.duration} (Timer) — длительность обработки.
 *       Теги: {@code strategy}.</li>
 *   <li>{@code exception.unhandled.total} (Counter) — количество неклассифицированных исключений
 *       (обработанных через {@code UnknownExceptionHandler}).
 *       Теги: {@code exception_type}.</li>
 * </ul>
 *
 * <h2>Кардинальность</h2>
 * <p>Тег {@code exception_type} имеет потенциально неограниченную кардинальность
 * при динамической генерации классов. Рекомендуется зарегистрировать
 * {@link io.micrometer.core.instrument.config.MeterFilter#maximumAllowableTags}
 * в {@code MeterRegistry} для ограничения.
 *
 * @see ExceptionMetricRecorder
 */
public class MicrometerExceptionMetricRecorder implements ExceptionMetricRecorder {

    public static final String METRIC_HANDLED = "exception.handled";
    public static final String METRIC_DURATION = "exception.handling.duration";
    public static final String METRIC_UNHANDLED = "exception.unhandled";
    /**
     * Счётчик таймаутов {@code ErrorEntryEnricher} (Sprint 1, C2).
     * Тег {@link #TAG_ENRICHER} — simple class name enricher-а; кардинальность
     * ограничена количеством enricher-бинов в контексте (обычно &lt; 20).
     */
    public static final String METRIC_ENRICHER_TIMEOUT = "error.handling.enricher.timeout";

    /**
     * Счётчик таймаутов асинхронного парсинга upstream JSON-тела (Sprint 2, C5 vart C).
     * Без тегов: per-enricher разбивка не применима, тип upstream-исключения
     * есть в основном {@link #METRIC_HANDLED}/{@link #METRIC_UNHANDLED} с тегом
     * {@link #TAG_EXCEPTION_TYPE}.
     */
    public static final String METRIC_UPSTREAM_PARSE_TIMEOUT = "error.handling.upstream.parse.timeout";

    public static final String TAG_STRATEGY = "strategy";
    public static final String TAG_STATUS_CLASS = "http_status_class";
    public static final String TAG_EXCEPTION_TYPE = "exception_type";
    public static final String TAG_ENRICHER = "enricher";

    private final MeterRegistry meterRegistry;

    public MicrometerExceptionMetricRecorder(@NonNull MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordHandled(@NonNull ExceptionMetricSnapshot snapshot) {
        Counter.builder(METRIC_HANDLED)
                .tag(TAG_STRATEGY, snapshot.strategyName())
                .tag(TAG_STATUS_CLASS, snapshot.httpStatusClass())
                .tag(TAG_EXCEPTION_TYPE, snapshot.exceptionType())
                .register(meterRegistry)
                .increment();

        Timer.builder(METRIC_DURATION)
                .tag(TAG_STRATEGY, snapshot.strategyName())
                .register(meterRegistry)
                .record(Duration.ofNanos(snapshot.durationNanos()));
    }

    @Override
    public void recordUnhandled(@NonNull ExceptionMetricSnapshot snapshot) {
        Counter.builder(METRIC_UNHANDLED)
                .tag(TAG_EXCEPTION_TYPE, snapshot.exceptionType())
                .register(meterRegistry)
                .increment();

        Timer.builder(METRIC_DURATION)
                .tag(TAG_STRATEGY, snapshot.strategyName())
                .register(meterRegistry)
                .record(Duration.ofNanos(snapshot.durationNanos()));
    }

    @Override
    public void recordEnricherTimeout(@NonNull Class<?> enricherType) {
        Counter.builder(METRIC_ENRICHER_TIMEOUT)
                .tag(TAG_ENRICHER, enricherType.getSimpleName())
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordUpstreamParseTimeout() {
        Counter.builder(METRIC_UPSTREAM_PARSE_TIMEOUT)
                .register(meterRegistry)
                .increment();
    }
}
