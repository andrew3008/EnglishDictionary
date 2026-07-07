package space.br1440.platform.tracing.autoconfigure.support;

import java.time.Duration;

/**
 * Утилитарный конвертер {@link Duration} в строковое представление целого числа миллисекунд.
 * <p>
 * Назначение — корректно сериализовать значения длительностей платформенной трассировки
 * для свойств OpenTelemetry SDK ({@code OTEL_BSP_*}, {@code OTEL_EXPORTER_OTLP_TIMEOUT} и т.п.).
 * Согласно <a href="https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/configuration/common.md">OTel
 * configuration spec</a> любое значение типа {@code Duration} в переменной окружения должно
 * быть целым числом миллисекунд (integer-ms). Suffix-парсер ({@code "100ms"}, {@code "1s"})
 * — это расширение Java SDK, не cross-language стандарт; платформа придерживается строгого
 * формата SPEC, чтобы конфигурация была переносима между OTel-имплементациями (Java, Go,
 * Python и т.д.) и между OTel agent / OTel Collector.
 *
 * <h2>Контракт</h2>
 * <ul>
 *   <li>{@code null} → {@link IllegalArgumentException};</li>
 *   <li>отрицательные {@code Duration} → {@link IllegalArgumentException}
 *       (требование OTel SPEC: «non-negative»);</li>
 *   <li>положительные / нулевые значения → строка вида {@code "100"}, {@code "30000"},
 *       {@code "0"}.</li>
 * </ul>
 *
 * <h2>Пример использования</h2>
 * <pre>{@code
 * Duration timeout = properties.getQueue().getExportTimeout();
 * String otelValue = DurationToMillis.toOtelString(timeout); // "100"
 * envVars.put("OTEL_BSP_EXPORT_TIMEOUT", otelValue);
 * }</pre>
 *
 * <h2>Почему утилита, а не {@code Duration#toMillis()} напрямую</h2>
 * <p>
 * Прямой вызов {@code String.valueOf(d.toMillis())} раскидан по нескольким местам кода
 * (платформенный SPI-mapper, рендер actuator-endpoint, потенциальные генераторы Helm-чартов).
 * Централизованная утилита:
 * <ul>
 *   <li>фиксирует контракт по null/отрицательным значениям в одном месте;</li>
 *   <li>делает явной отсылку к OTel SPEC в Javadoc'е (на случай, если разработчик подумает,
 *       что Java-suffix формат тоже сработает);</li>
 *   <li>облегчает поиск всех мест преобразования при будущих изменениях SPEC.</li>
 * </ul>
 */
public final class DurationToMillis {

    private DurationToMillis() {
        // утилитарный класс
    }

    /**
     * Преобразует {@link Duration} в строку вида {@code "<millis>"} для свойства OTel SDK.
     *
     * @param duration длительность; обязана быть не {@code null} и не отрицательной
     * @return строковое представление количества миллисекунд (integer-ms)
     * @throws IllegalArgumentException если {@code duration} равен {@code null} или отрицательный
     */
    public static String toOtelString(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("duration must not be null");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException(
                    "duration must be non-negative according to OTel SPEC, got: " + duration);
        }
        return Long.toString(duration.toMillis());
    }
}
