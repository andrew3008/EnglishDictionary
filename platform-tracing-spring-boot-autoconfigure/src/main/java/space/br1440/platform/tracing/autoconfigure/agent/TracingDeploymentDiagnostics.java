package space.br1440.platform.tracing.autoconfigure.agent;

/**
 * Снимок строгой проверки runtime на старте Spring-контекста.
 * <p>
 * Иммутабельный носитель для диагностики ({@code /actuator/tracingStatus}) и логирования.
 * Содержит только безопасные operator-facing признаки. Failure message из Agent-side MBean
 * намеренно не переносится в application diagnostics.
 *
 * @param tracingEnabled настроенный и проверенный intent ({@code platform.tracing.enabled})
 * @param agentDetected  обнаружен ли OTel Java Agent в текущей JVM
 */
public record TracingDeploymentDiagnostics(
        boolean tracingEnabled,
        boolean agentDetected,
        AgentRuntimeState runtimeState,
        AgentExtensionDescriptor extensionDescriptor) {
}
