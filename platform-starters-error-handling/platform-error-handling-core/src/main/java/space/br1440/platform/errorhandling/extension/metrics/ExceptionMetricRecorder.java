package space.br1440.platform.errorhandling.extension.metrics;

import org.springframework.lang.NonNull;

/**
 * SPI для записи метрик обработки исключений.
 *
 * <p>Вызывается {@code GlobalExceptionHandler} / реактивным обработчиком после каждой обработки исключения.
 * Реализации получают всю необходимую информацию через {@link ExceptionMetricSnapshot}.
 *
 * <p>По умолчанию регистрируется {@code NoopExceptionMetricRecorder} (нулевой overhead).
 * При наличии Micrometer в classpath автоматически активируется
 * {@code MicrometerExceptionMetricRecorder}.
 *
 * <p>Для кастомной реализации объявить {@code @Bean} в конфигурации микросервиса:
 * <pre>{@code
 * @Bean
 * public ExceptionMetricRecorder customMetricRecorder(MeterRegistry registry) {
 *     return new MyCustomExceptionMetricRecorder(registry);
 * }
 * }</pre>
 *
 * @see ExceptionMetricSnapshot
 */
public interface ExceptionMetricRecorder {

    /**
     * Фиксирует метрику успешно классифицированного исключения.
     */
    void recordHandled(@NonNull ExceptionMetricSnapshot snapshot);

    /**
     * Фиксирует метрику неклассифицированного исключения, обработанного через {@code UnknownExceptionHandler}.
     *
     * <p>Рост этого счётчика сигнализирует о появлении новых типов исключений,
     * не покрытых ни одной стратегией — требует добавления стратегии или расширения существующей.
     */
    void recordUnhandled(@NonNull ExceptionMetricSnapshot snapshot);

    /**
     * Фиксирует таймаут одного {@code ErrorEntryEnricher} (Sprint 1, C2).
     *
     * <p>Default-реализация — no-op для backward-совместимости с пользовательскими
     * recorder'ами, которые ещё не знают про этот вид метрики. Платформенные
     * реализации ({@code MicrometerExceptionMetricRecorder}) override-ят и
     * пишут counter {@code error_handling_enricher_timeout_total} с тегом
     * {@code enricher=<simpleName>}.
     *
     * <p>Резкий рост этой метрики означает, что enricher держит event-loop
     * (или boundedElastic) дольше {@code platform.error-handling.enricher.timeout-ms}
     * — нужно либо сделать его быстрее, либо пересмотреть {@code timeout-ms}
     * с учётом p99 enricher-а.
     *
     * @param enricherType класс enricher-а, на котором сработал таймаут
     */
    default void recordEnricherTimeout(@NonNull Class<?> enricherType) {
        // no-op: пользовательские recorder'ы обрабатывают на своё усмотрение
    }

    /**
     * Фиксирует таймаут асинхронного парсинга upstream JSON-тела (Sprint 2, C5 vart C).
     *
     * <p>Default-реализация — no-op (back-compat для пользовательских recorder'ов).
     * Платформенные реализации ({@code MicrometerExceptionMetricRecorder})
     * пишут counter {@code error.handling.upstream.parse.timeout}.
     *
     * <p>Резкий рост этой метрики означает, что парсинг upstream-тела
     * систематически не укладывается в
     * {@code platform.error-handling.upstream.parse-timeout-ms}. Возможные
     * причины: pool contention на {@code Schedulers.boundedElastic()}, очень
     * крупные тела на грани {@link Class}-лимита {@code upstream.maxBodyBytes},
     * или CPU-stall ноды.
     */
    default void recordUpstreamParseTimeout() {
        // no-op: пользовательские recorder'ы обрабатывают на своё усмотрение
    }

}
