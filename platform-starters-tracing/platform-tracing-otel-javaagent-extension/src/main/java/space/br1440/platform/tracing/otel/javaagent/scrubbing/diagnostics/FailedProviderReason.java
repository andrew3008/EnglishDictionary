package space.br1440.platform.tracing.otel.javaagent.scrubbing.diagnostics;

/**
 * Причина, по которой провайдер кастомного правила не был загружен.
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
    INSTANTIATION_ERROR,

    /** Провайдер создал правило с недопустимыми или нестабильными метаданными. */
    INVALID_METADATA

}
