package space.br1440.platform.tracing.autoconfigure.support;

/**
 * Application-side классификация runtime без доверия к одному classpath marker.
 */
public enum AgentRuntimeState {
    DISABLED,
    AGENT_READY,
    AGENT_MISSING,
    EXTENSION_INITIALIZING,
    EXTENSION_MISSING,
    EXTENSION_INCOMPATIBLE,
    EXTENSION_FAILED,
    DUAL_SDK_DETECTED
}
