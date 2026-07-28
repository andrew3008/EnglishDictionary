package space.br1440.platform.tracing.otel.javaagent.control.state;

/**
 * Результат одной попытки изменения accepted runtime policy через legacy writer posture.
 */
public enum AcceptedPolicyUpdateOutcome {
    APPLIED,
    UNCHANGED,
    STALE,
    REJECTED
}
