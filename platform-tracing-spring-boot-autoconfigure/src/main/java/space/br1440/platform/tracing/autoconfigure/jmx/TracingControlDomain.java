package space.br1440.platform.tracing.autoconfigure.jmx;

/**
 * Перечисление шести доменов платформенной JMX-плоскости управления трассировкой.
 * Используется в {@link PlatformTracingJmxClient#getMBeansStatus()} и в логике
 * {@code RuntimeConfigApplier} для безопасной идентификации доменов на этапе компиляции.
 */
public enum TracingControlDomain {
    SAMPLING,
    SCRUBBING,
    VALIDATION,
    EXPORT,
    PROCESSOR_METRICS,
    DIAGNOSTICS
}
