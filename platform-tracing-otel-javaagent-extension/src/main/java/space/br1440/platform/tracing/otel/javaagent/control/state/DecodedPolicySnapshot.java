package space.br1440.platform.tracing.otel.javaagent.control.state;

record DecodedPolicySnapshot(
        PolicyApplyAttempt attempt,
        ExpectedPolicyBase expectedBase,
        String candidateDigest,
        EffectiveTracingPolicy policy) {
}
