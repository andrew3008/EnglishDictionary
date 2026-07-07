package space.br1440.platform.tracing.core.sampling.model;

public record SamplingPolicyRequest(
        String urlPath,
        String traceId,
        String forceTraceHeaderValue,
        boolean qaTrace,
        ParentContextState parentContextState
) {

    public SamplingPolicyRequest(String urlPath) {
        this(urlPath, null, null, false, ParentContextState.ABSENT);
    }
}
