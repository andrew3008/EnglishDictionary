package space.br1440.platform.tracing.autoconfigure.status;

/**
 * Детерминированная причина отсутствия принятой runtime-политики в status read-model.
 */
public enum PolicyAvailabilityReason {
    AUTHORITY_HAS_NO_ACCEPTED_POLICY,
    TRACING_DISABLED,
    AGENT_UNAVAILABLE,
    POLICY_NOT_ACCEPTED
}
