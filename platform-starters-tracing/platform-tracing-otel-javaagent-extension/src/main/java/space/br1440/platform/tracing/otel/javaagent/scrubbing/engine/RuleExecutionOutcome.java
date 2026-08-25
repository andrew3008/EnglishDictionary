package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

public enum RuleExecutionOutcome {
    KEEP,
    DECISION,
    SKIPPED_CIRCUIT_OPEN,
    NON_CRITICAL_FAILURE,
    CRITICAL_FAILURE
}
