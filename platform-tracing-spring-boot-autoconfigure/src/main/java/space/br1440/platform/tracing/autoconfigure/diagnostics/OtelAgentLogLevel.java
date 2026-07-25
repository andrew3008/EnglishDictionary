package space.br1440.platform.tracing.autoconfigure.diagnostics;

import java.util.Locale;

/**
 * Допустимые уровни внутренних логов платформы в Java Agent.
 * <p>
 * Имена констант образуют стабильные значения строкового JMX-контракта между application
 * classloader и agent extension classloader.
 */
public enum OtelAgentLogLevel {
    OFF,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE;

    /**
     * Преобразует внешнее строковое представление в уровень без зависимости от регистра.
     *
     * @param value строковое представление уровня
     * @return распознанный уровень
     * @throws IllegalArgumentException если значение отсутствует или не поддерживается
     */
    public static OtelAgentLogLevel parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Log level must not be blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported platform log level: " + value
                            + " (expected OFF/ERROR/WARN/INFO/DEBUG/TRACE)",
                    e);
        }
    }
}
