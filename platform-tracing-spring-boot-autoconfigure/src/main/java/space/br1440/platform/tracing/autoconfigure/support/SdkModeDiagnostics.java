package space.br1440.platform.tracing.autoconfigure.support;

/**
 * Снимок резолва режима SDK на старте контекста (Фаза 15, PR-3).
 * <p>
 * Иммутабельный носитель для диагностики ({@code /actuator/tracing}) и логирования: фиксирует
 * эффективный {@link SdkMode} и факт обнаружения OTel Java Agent. Создаётся один раз в
 * {@code TracingCoreAutoConfiguration}; SDK при этом не создаётся (agent-first).
 *
 * @param mode          эффективный режим (после {@link SdkModeResolver})
 * @param agentDetected обнаружен ли OTel Java Agent в текущей JVM
 */
public record SdkModeDiagnostics(
        SdkMode mode,
        boolean agentDetected,
        AgentRuntimeState runtimeState,
        AgentExtensionDescriptor extensionDescriptor) {

    public SdkModeDiagnostics(SdkMode mode, boolean agentDetected) {
        this(
                mode,
                agentDetected,
                agentDetected ? AgentRuntimeState.AGENT_READY : AgentRuntimeState.AGENT_MISSING,
                null);
    }
}
