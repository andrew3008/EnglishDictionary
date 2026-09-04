package space.br1440.platform.tracing.otel.javaagent.jmx.security;

public enum EvidenceCaptureOutcome {

    COMPLETE,
    SYSTEM_PROPERTIES_UNAVAILABLE,
    JVM_ARGUMENTS_UNAVAILABLE,
    CURRENT_PROCESS_EVIDENCE_UNAVAILABLE;

    boolean complete() {
        return (this == COMPLETE);
    }
}
