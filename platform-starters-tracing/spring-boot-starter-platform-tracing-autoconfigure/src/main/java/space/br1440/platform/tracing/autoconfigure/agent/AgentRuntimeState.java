package space.br1440.platform.tracing.autoconfigure.agent;

public enum AgentRuntimeState {
    DISABLED,
    AGENT_READY,
    AGENT_MISSING,
    EXTENSION_INITIALIZING,
    EXTENSION_MISSING,
    EXTENSION_INCOMPATIBLE,
    EXTENSION_FAILED,
    CAPABILITY_MISSING,
    DUAL_SDK_DETECTED
}
