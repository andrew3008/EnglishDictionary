package space.br1440.platform.tracing.otel.sampling.model;

public enum SamplingPolicyDecisionType {
    DROP,
    RECORD_ONLY,
    RECORD_AND_SAMPLE,
    ABSTAIN
}
