package space.br1440.platform.tracing.otel.javaagent.readiness;

/**
 * Возможности (capabilities), обязательные для профиля безопасного расширения платформы.
 * <p>
 * Каждое перечисленное значение обязательно для перехода в состояние READY. Сгенерированный JMX-контракт
 * зеркально повторяет эти имена, а contract-тест не допускает расхождения между двумя build-артефактами.
 */
public enum PlatformExtensionCapability {
    CONFIGURATION_LOADED,
    PLATFORM_SAMPLER_INSTALLED,
    REQUIRED_SPAN_PROCESSORS_INSTALLED,
    SANITIZER_INSTALLED,
    PROPAGATION_HOOKS_INSTALLED,
    SAFE_EXPORTER_INSTALLED,
    EXPORT_PATH_PROTECTED
}
