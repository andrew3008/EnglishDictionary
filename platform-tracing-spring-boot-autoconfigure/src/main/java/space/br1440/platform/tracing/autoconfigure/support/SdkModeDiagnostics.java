package space.br1440.platform.tracing.autoconfigure.support;

/**
 * Снимок строгой проверки runtime на старте Spring-контекста.
 * <p>
 * Иммутабельный носитель для диагностики ({@code /actuator/tracing}) и логирования: фиксирует
 * Содержит только безопасные operator-facing признаки. Failure message из Agent-side MBean
 * намеренно не переносится в application diagnostics.
 *
 * @param mode          настроенный и проверенный production-режим
 * @param enabled       значение {@code platform.tracing.enabled}
 * @param agentDetected обнаружен ли OTel Java Agent в текущей JVM
 */
public record SdkModeDiagnostics(
        SdkMode mode,
        boolean enabled,
        boolean agentDetected,
        AgentRuntimeState runtimeState,
        AgentExtensionDescriptor extensionDescriptor) {
}
