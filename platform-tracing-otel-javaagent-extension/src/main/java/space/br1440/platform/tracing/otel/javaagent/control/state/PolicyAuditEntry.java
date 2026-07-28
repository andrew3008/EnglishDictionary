package space.br1440.platform.tracing.otel.javaagent.control.state;

record PolicyAuditEntry(
        PolicyApplyAttempt attempt,
        PolicyApplyOutcome outcome,
        PolicyMessageCode messageCode,
        long acceptedSequence,
        String payloadDigest) {
}
