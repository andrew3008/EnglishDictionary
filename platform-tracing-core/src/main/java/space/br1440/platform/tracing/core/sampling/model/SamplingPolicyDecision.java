package space.br1440.platform.tracing.core.sampling.model;

public record SamplingPolicyDecision(
        SamplingPolicyDecisionType decisionType,
        SamplingPolicyReason reason,
        String winningRule
) {

    public SamplingPolicyDecision {
        if (decisionType == SamplingPolicyDecisionType.ABSTAIN) {
            if (reason != SamplingPolicyReason.NO_MATCH) {
                throw new IllegalArgumentException("ABSTAIN requires NO_MATCH reason");
            }

            if (winningRule != null && !winningRule.isEmpty()) {
                throw new IllegalArgumentException("ABSTAIN must not carry a winning rule");
            }
        } else {
            if (reason == null || reason == SamplingPolicyReason.NO_MATCH) {
                throw new IllegalArgumentException("decision requires a concrete reason");
            }

            if (winningRule == null || winningRule.isEmpty()) {
                throw new IllegalArgumentException("decision requires a winning rule");
            }
        }
    }

    public static SamplingPolicyDecision drop(SamplingPolicyReason reason, String winningRule) {
        return new SamplingPolicyDecision(SamplingPolicyDecisionType.DROP, reason, winningRule);
    }

    public static SamplingPolicyDecision recordAndSample(SamplingPolicyReason reason, String winningRule) {
        return new SamplingPolicyDecision(SamplingPolicyDecisionType.RECORD_AND_SAMPLE, reason, winningRule);
    }

    public static SamplingPolicyDecision abstain() {
        return new SamplingPolicyDecision(
                SamplingPolicyDecisionType.ABSTAIN,
                SamplingPolicyReason.NO_MATCH,
                null);
    }
}
