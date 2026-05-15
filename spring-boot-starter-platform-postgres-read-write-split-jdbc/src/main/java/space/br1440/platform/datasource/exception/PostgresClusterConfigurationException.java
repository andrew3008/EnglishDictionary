package space.br1440.platform.datasource.exception;

import space.br1440.platform.errorhandling.startup.PlatformConfigurationException;

/**
 * Исключение конфигурации PostgreSQL-кластера: невалидные параметры подключения,
 * ошибки парсинга driver-properties, конфликтные стратегии и т.п.
 *
 * <p>Бросается на старте контекста (fail-fast), чтобы ошибка проявилась до выхода
 * приложения в обслуживание клиентов. Все нарушения конфигурации стартеров
 * platform-postgres-cluster нормализуются именно к этому типу.
 */
public class PostgresClusterConfigurationException extends PlatformConfigurationException {

    public PostgresClusterConfigurationException(String message, String action) {
        super(message, action);
    }

    /**
     * Конструктор с причиной (cause). Используется в обёртках над низкоуровневыми
     * парсерами (например, {@code Duration.parse} → {@code DateTimeParseException}),
     * чтобы сохранить оригинальную диагностику в stack trace.
     *
     * <p>Реализация через {@link #initCause(Throwable)} устойчива к любому набору
     * конструкторов базового {@link PlatformConfigurationException}.
     */
    public PostgresClusterConfigurationException(String message, String action, Throwable cause) {
        super(message, action);
        initCause(cause);
    }
}
