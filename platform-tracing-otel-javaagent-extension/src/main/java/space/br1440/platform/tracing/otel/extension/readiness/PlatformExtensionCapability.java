package space.br1440.platform.tracing.otel.extension.readiness;

/**
 * Возможности, подтверждаемые фактическими callback'ами SDK autoconfigure.
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
