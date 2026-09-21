package space.br1440.platform.tracing.otel.javaagent.jmx.security;

public enum RemoteJmxDenialReason {

    NONE,
    REMOTE_JMX_EVIDENCE_UNKNOWN,
    REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED;

    boolean denies() {
        return (this != NONE);
    }
}
