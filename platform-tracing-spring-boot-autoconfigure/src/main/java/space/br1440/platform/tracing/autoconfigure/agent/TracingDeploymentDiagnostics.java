package space.br1440.platform.tracing.autoconfigure.agent;

public record TracingDeploymentDiagnostics(
        boolean tracingEnabled,
        boolean agentDetected,
        AgentRuntimeState runtimeState,
        AgentExtensionDescriptor extensionDescriptor) {
}
