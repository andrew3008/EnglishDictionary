package space.br1440.platform.tracing.otel.extension.scrubbing.diagnostics;

/**
 * Причина, по которой провайдер кастомного правила не был загружен.
 * <p>
 * Используется только в диагностике (startup-лог, JMX/Actuator). В метки Prometheus
 * <b>не</b> выносится во избежание high-cardinality.
 */
public enum FailedProviderReason {

    /** Запись {@code META-INF/services} привела к {@code ServiceConfigurationError}. */
    SERVICE_CONFIGURATION_ERROR,

    /** JAR указан одновременно в {@code otel.javaagent.extensions} и в свойстве правил. */
    DUPLICATE_CONFIG,

    /** JAR содержит запрещённые platform/OTel-классы (нарушение JAR hygiene). */
    FORBIDDEN_CLASSES,

    /** Путь к файлу существует, но не читается. */
    UNREADABLE_FILE,

    /** Сконфигурированная директория или файл не существуют. */
    MISSING_PATH,

    /** Класс правила загрузился, но не создался (ошибка инстанцирования). */
    INSTANTIATION_ERROR
}
