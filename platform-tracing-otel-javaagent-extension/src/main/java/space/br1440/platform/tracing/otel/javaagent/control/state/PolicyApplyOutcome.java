package space.br1440.platform.tracing.otel.javaagent.control.state;

enum PolicyApplyOutcome {
    APPLIED,
    UNCHANGED,
    REJECTED,
    STALE,
    FAILED
}
