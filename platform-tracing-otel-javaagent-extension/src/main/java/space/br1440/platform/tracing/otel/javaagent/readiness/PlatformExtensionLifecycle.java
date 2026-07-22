package space.br1440.platform.tracing.otel.javaagent.readiness;

/**
 * Наблюдаемое состояние инициализации platform Java Agent extension.
 */
public enum PlatformExtensionLifecycle {
    INITIALIZING,
    READY,
    FAILED
}
