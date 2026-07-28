package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * @deprecated Replaced by three separate truth-category records:
 * {@link AgentReadinessView}, {@link AcceptedPolicyLeaves}, {@link TracingApplyDiagnostics}.
 * Kept only to avoid compilation breaks in any existing call sites during migration.
 * Will be removed before P5.
 */
@Deprecated(forRemoval = true)
public record AcceptedTracingPolicyView(
        String runtimeInstanceId,
        long acceptedSequence,
        String policyDigest,
        boolean samplingEnabled,
        double samplingRatio,
        int samplingRouteRatiosCount,
        boolean validationEnabled,
        boolean validationStrict,
        boolean exporterEnabled,
        boolean traceControlHeadersEnabled,
        String diagnosticsLogLevel,
        boolean agentReady,
        String latestApplyOutcome
) {}
