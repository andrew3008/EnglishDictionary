package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Objects;

record PolicyApplyResult(
        PolicyApplyOutcome outcome,
        AcceptedPolicyEnvelope accepted,
        String payloadDigest,
        PolicyMessageCode messageCode) {

    PolicyApplyResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        Objects.requireNonNull(messageCode, "messageCode");
        if (!payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadDigest must be lowercase SHA-256 hexadecimal");
        }
        boolean successful = outcome == PolicyApplyOutcome.APPLIED
                || outcome == PolicyApplyOutcome.UNCHANGED;
        if (successful != (messageCode == PolicyMessageCode.NONE)) {
            throw new IllegalArgumentException("Apply outcome and message code are inconsistent");
        }
    }
}
