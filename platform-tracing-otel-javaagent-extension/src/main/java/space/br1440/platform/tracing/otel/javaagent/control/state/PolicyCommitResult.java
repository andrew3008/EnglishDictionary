package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Objects;

record PolicyCommitResult(
        PolicyCommitOutcome outcome,
        AcceptedPolicyEnvelope accepted) {

    PolicyCommitResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(accepted, "accepted");
    }
}
