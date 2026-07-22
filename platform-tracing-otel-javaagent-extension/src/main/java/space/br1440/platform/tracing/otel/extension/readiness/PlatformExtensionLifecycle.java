package space.br1440.platform.tracing.otel.extension.readiness;

/**
 * Наблюдаемое состояние инициализации platform Java Agent extension.
 */
public enum PlatformExtensionLifecycle {
    INITIALIZING,
    READY,
    FAILED
}
