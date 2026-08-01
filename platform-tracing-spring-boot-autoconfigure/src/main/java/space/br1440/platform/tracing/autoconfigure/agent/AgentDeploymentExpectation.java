package space.br1440.platform.tracing.autoconfigure.agent;

/**
 * Ожидание observer относительно agent deployment на startup.
 *
 * <p>Internal cross-package contract. Не является application API/SPI.
 * Допустимые потребители: {@code TracingRuntimeAutoConfiguration},
 * {@link AgentExtensionObserver}, тесты owning-модуля.</p>
 */
public enum AgentDeploymentExpectation {
    /** {@code platform.tracing.enabled=true} — Agent обязан стать READY. */
    REQUIRED,
    /**
     * {@code platform.tracing.enabled=false} — Agent обязан отсутствовать в течение
     * startup validation window. Dynamic attach после завершения Spring startup
     * не поддерживается и этим starter не контролируется.
     */
    FORBIDDEN
}
